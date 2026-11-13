package cliente;

import comum.Mensagens;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cliente de consola do sistema de leiloes.
 *
 * Funcionalidades:
 *  - Envio interativo de comandos do Anexo A por TCP (UTF-8, terminados em '\n').
 *  - Escuta em tempo real de avisos UDP multicast numa thread dedicada (I1).
 *  - Detecao de perdas por numero de sequencia (seq > ultimo + 1) e recuperacao via TCP ESTADO <id> (I2).
 *  - Opcao --descartar N (ou comando DESCARTAR N) para simular perdas 1 em N na demonstracao (I2).
 */
public class Cliente {
    private static final Map<Integer, Integer> acompanhados = new HashMap<>();
    private static volatile int descartarCadaN = 0;
    private static int contadorRecebidosAcompanhados = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.net.preferIPv4Stack", "true");

        String host = "localhost";
        int portoTcp = 5050;
        String grupoMulticast = "239.255.0.1";
        int portoUdp = 6060;
        String iniciaisAcompanhar = null;
        boolean soAcompanhar = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                    host = args[++i];
                    break;
                case "--porto":
                    portoTcp = Integer.parseInt(args[++i]);
                    break;
                case "--grupo":
                    grupoMulticast = args[++i];
                    break;
                case "--udp":
                    portoUdp = Integer.parseInt(args[++i]);
                    break;
                case "--descartar":
                    descartarCadaN = Integer.parseInt(args[++i]);
                    break;
                case "--acompanhar":
                    iniciaisAcompanhar = args[++i];
                    break;
                case "--so-acompanhar":
                    soAcompanhar = true;
                    break;
                case "--ajuda":
                case "--help":
                    imprimirAjudaCli();
                    return;
                default:
                    System.err.println("Argumento desconhecido: " + args[i]);
                    imprimirAjudaCli();
                    System.exit(1);
            }
        }

        final String srvHost = host;
        final int srvPorto = portoTcp;
        final String mcastGrupo = grupoMulticast;
        final int mcastPorto = portoUdp;
        final AtomicBoolean ativo = new AtomicBoolean(true);

        try (Socket tcpSocket = new Socket(srvHost, srvPorto);
             BufferedReader tcpIn = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream tcpOut = tcpSocket.getOutputStream();
             MulticastSocket mcastSocket = new MulticastSocket(mcastPorto)) {

            InetAddress grupoAddr = InetAddress.getByName(mcastGrupo);
            mcastSocket.joinGroup(new InetSocketAddress(grupoAddr, mcastPorto), null);

            Thread listener = new Thread(
                    () -> escutarMulticast(mcastSocket, srvHost, srvPorto, ativo),
                    "MulticastListener");
            listener.setDaemon(true);
            listener.start();

            System.out.println("=== Cliente de Leiloes ===");
            System.out.println("Ligado a " + srvHost + ":" + srvPorto
                    + " | Multicast " + mcastGrupo + ":" + mcastPorto
                    + (descartarCadaN > 0 ? " | Simular perda: 1 em cada " + descartarCadaN : ""));
            imprimirComandos();

            if (iniciaisAcompanhar != null && !iniciaisAcompanhar.isEmpty()) {
                for (String parte : iniciaisAcompanhar.split(",")) {
                    String s = parte.trim();
                    if (!s.isEmpty()) {
                        iniciarAcompanhamento(srvHost, srvPorto, Integer.parseInt(s), true);
                    }
                }
            }

            BufferedReader consola = new BufferedReader(new InputStreamReader(System.in));
            while (ativo.get()) {
                System.out.print("> ");
                System.out.flush();
                String linha = consola.readLine();
                if (linha == null) {
                    if (soAcompanhar) {
                        while (ativo.get()) {
                            Thread.sleep(500);
                        }
                    } else {
                        enviarTcp(tcpOut, "SAIR");
                        tcpIn.readLine();
                    }
                    break;
                }

                String cmdUpper = linha.trim();
                if (cmdUpper.equalsIgnoreCase("AJUDA") || cmdUpper.equalsIgnoreCase("HELP")) {
                    imprimirComandos();
                    continue;
                }
                if (cmdUpper.startsWith("ACOMPANHAR ")) {
                    String arg = cmdUpper.substring("ACOMPANHAR ".length()).trim();
                    try {
                        int id = Integer.parseInt(arg);
                        iniciarAcompanhamento(srvHost, srvPorto, id, false);
                    } catch (NumberFormatException e) {
                        System.out.println("Uso: ACOMPANHAR <id>");
                    }
                    continue;
                }
                if (cmdUpper.startsWith("PARAR ")) {
                    String arg = cmdUpper.substring("PARAR ".length()).trim();
                    try {
                        int id = Integer.parseInt(arg);
                        synchronized (acompanhados) {
                            acompanhados.remove(id);
                        }
                        System.out.println("Deixou de acompanhar leilao #" + id);
                    } catch (NumberFormatException e) {
                        System.out.println("Uso: PARAR <id>");
                    }
                    continue;
                }
                if (cmdUpper.startsWith("DESCARTAR ")) {
                    String arg = cmdUpper.substring("DESCARTAR ".length()).trim();
                    try {
                        int n = Integer.parseInt(arg);
                        descartarCadaN = Math.max(0, n);
                        System.out.println(descartarCadaN == 0
                                ? "Simulacao de perdas desativada."
                                : "A descartar 1 de cada " + descartarCadaN + " avisos recebidos.");
                    } catch (NumberFormatException e) {
                        System.out.println("Uso: DESCARTAR <N> (0 para desativar)");
                    }
                    continue;
                }

                enviarTcp(tcpOut, linha);
                String resp = tcpIn.readLine();
                if (resp == null) {
                    System.out.println("[TCP] Ligacao encerrada pelo servidor.");
                    break;
                }
                System.out.println(resp);

                if (linha.equals("LISTAR") && resp.startsWith("OK ")) {
                    int n = Integer.parseInt(resp.substring(3).trim());
                    for (int i = 0; i < n; i++) {
                        String item = tcpIn.readLine();
                        if (item != null) {
                            System.out.println(item);
                        }
                    }
                }

                if (linha.equals("SAIR") && resp.equals("OK")) {
                    break;
                }
            }

            ativo.set(false);
            try {
                mcastSocket.leaveGroup(new InetSocketAddress(grupoAddr, mcastPorto), null);
            } catch (IOException ignored) {
            }
        }
    }

    private static void iniciarAcompanhamento(String host, int porto, int id, boolean permitirInexistente) {
        String estadoResp = consultarEstadoTcp(host, porto, id);
        if (estadoResp == null) {
            System.out.println("[ERRO] Nao foi possivel obter estado do leilao #" + id);
            return;
        }
        if (estadoResp.startsWith("ERRO INEXISTENTE")) {
            if (permitirInexistente) {
                synchronized (acompanhados) {
                    acompanhados.putIfAbsent(id, 0);
                }
                System.out.println("[ACOMPANHAR #" + id + "] Leilao ainda nao existe; a aguardar avisos a partir de seq=1...");
            } else {
                System.out.println(estadoResp);
            }
            return;
        }
        if (!estadoResp.startsWith("OK ")) {
            System.out.println(estadoResp);
            return;
        }
        Map<String, String> campos = Mensagens.parseChaveValor(estadoResp.substring(3));
        int seq = Integer.parseInt(campos.getOrDefault("seq", "0"));
        synchronized (acompanhados) {
            acompanhados.merge(id, seq, Math::max);
        }
        System.out.println("[ACOMPANHAR #" + id + "] Estado atual (" + estadoResp + ")");
    }

    private static void escutarMulticast(MulticastSocket mcastSocket, String host, int porto, AtomicBoolean ativo) {
        byte[] buf = new byte[2048];
        while (ativo.get() && !mcastSocket.isClosed()) {
            try {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                mcastSocket.receive(pkt);
                String msg = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8).trim();
                if (!msg.startsWith("AVISO ")) {
                    continue;
                }
                Map<String, String> campos = Mensagens.parseChaveValor(msg.substring(6));
                int id = Integer.parseInt(campos.get("id"));
                int seq = Integer.parseInt(campos.get("seq"));

                boolean recuperar = false;
                int seqEsperado = -1;
                boolean descartado = false;

                synchronized (acompanhados) {
                    Integer ultimo = acompanhados.get(id);
                    if (ultimo == null) {
                        continue;
                    }
                    if (descartarCadaN > 0) {
                        contadorRecebidosAcompanhados++;
                        if (contadorRecebidosAcompanhados % descartarCadaN == 0) {
                            descartado = true;
                        }
                    }
                    if (!descartado) {
                        if (seq <= ultimo) {
                            continue;
                        }
                        if (seq == ultimo + 1) {
                            acompanhados.put(id, seq);
                        } else {
                            seqEsperado = ultimo + 1;
                            recuperar = true;
                        }
                    }
                }

                if (descartado) {
                    System.out.println("\n[SIMULACAO PERDA] Aviso UDP descartado: leilao #" + id + ", seq=" + seq);
                    continue;
                }

                if (recuperar) {
                    System.out.println("\n[PERDA DETETADA] Leilao #" + id
                            + ": esperava seq=" + seqEsperado + ", chegou seq=" + seq
                            + ". A recuperar estado por TCP...");
                    String estadoTcp = consultarEstadoTcp(host, porto, id);
                    if (estadoTcp != null && estadoTcp.startsWith("OK ")) {
                        Map<String, String> dados = Mensagens.parseChaveValor(estadoTcp.substring(3));
                        int seqTcp = Integer.parseInt(dados.getOrDefault("seq", Integer.toString(seq)));
                        synchronized (acompanhados) {
                            acompanhados.merge(id, seqTcp, Math::max);
                        }
                        System.out.println("[RECUPERADO TCP] Leilao #" + id + " -> " + estadoTcp);
                    } else {
                        System.out.println("[ERRO RECUPERACAO] Resposta TCP: " + estadoTcp);
                    }
                } else {
                    imprimirAvisoTempoReal(id, seq, campos);
                }
            } catch (IOException e) {
                if (ativo.get() && !mcastSocket.isClosed()) {
                    System.err.println("[MULTICAST] Erro de rececao: " + e.getMessage());
                }
            } catch (Exception e) {
                System.err.println("[MULTICAST] Aviso mal formado ignorado: " + e.getMessage());
            }
        }
    }

    private static void imprimirAvisoTempoReal(int id, int seq, Map<String, String> c) {
        String tipo = c.getOrDefault("tipo", "?");
        switch (tipo) {
            case "LICITACAO":
                String extra = "true".equals(c.get("adiado")) ? " [ANTI-SNIPING: fecho adiado]" : "";
                System.out.println("\n[AVISO #" + id + " seq=" + seq + "] Nova licitacao: "
                        + c.get("valor") + " por " + c.get("licitador")
                        + " (restante: " + c.get("restante") + "s)" + extra);
                break;
            case "FECHO":
                String venc = c.getOrDefault("vencedor", "-");
                String txtVenc = "-".equals(venc) ? "sem vencedor" : ("vencedor=" + venc);
                System.out.println("\n[AVISO #" + id + " seq=" + seq + "] LEILAO FECHADO: "
                        + txtVenc + ", melhor=" + c.get("melhor") + ", licitacoes=" + c.get("licitacoes"));
                break;
            case "CANCELADO":
                System.out.println("\n[AVISO #" + id + " seq=" + seq + "] LEILAO CANCELADO pela administracao (sem vencedor).");
                break;
            default:
                System.out.println("\n[AVISO #" + id + " seq=" + seq + "] " + c);
        }
    }

    private static String consultarEstadoTcp(String host, int porto, int id) {
        try (Socket s = new Socket(host, porto);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = s.getOutputStream()) {
            s.setSoTimeout(5000);
            enviarTcp(out, "ESTADO " + id);
            String resp = in.readLine();
            enviarTcp(out, "SAIR");
            return resp;
        } catch (IOException e) {
            return null;
        }
    }

    private static void enviarTcp(OutputStream out, String linha) throws IOException {
        out.write((linha + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void imprimirComandos() {
        System.out.println("Comandos protocolo: REGISTAR <u> <pw> | LOGIN <u> <pw> | CRIAR <base> <inc> <dur> <artigo>");
        System.out.println("                    LISTAR | CONSULTAR <id> | ESTADO <id> | LICITAR <id> <valor> | SAIR");
        System.out.println("Comandos locais   : ACOMPANHAR <id> | PARAR <id> | DESCARTAR <N> | AJUDA");
    }

    private static void imprimirAjudaCli() {
        System.out.println("Uso: java -Djava.net.preferIPv4Stack=true -cp out cliente.Cliente [opcoes]");
        System.out.println("  --host <host>      Host do servidor (omissao: localhost)");
        System.out.println("  --porto <n>        Porto TCP (omissao: 5050)");
        System.out.println("  --grupo <ip>       Grupo multicast (omissao: 239.255.0.1)");
        System.out.println("  --udp <n>          Porto UDP multicast (omissao: 6060)");
        System.out.println("  --descartar <N>    Descarta 1 em cada N avisos recebidos (omissao: 0)");
        System.out.println("  --acompanhar <ids> IDs de leiloes a acompanhar logo no arranque (ex: 1,2)");
        System.out.println("  --so-acompanhar    Mantem-se ativo mesmo se stdin fechar");
    }
}

