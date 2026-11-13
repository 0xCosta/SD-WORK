package demo;

import comum.AdminRMI;
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
import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ferramenta de demonstracao rapida (1 comando por cenario) para avaliacao
 * e teste automatico dos requisitos do Trabalho Pratico.
 */
public class DemoCenarios {
    private static String host = "localhost";
    private static int portoTcp = 5050;
    private static int portoRmi = 5051;
    private static String grupoMcast = "239.255.0.1";
    private static int portoUdp = 6060;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.net.preferIPv4Stack", "true");

        if (args.length == 0) {
            imprimirUso();
            return;
        }

        String cenario = args[0];
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--host":
                    host = args[++i];
                    break;
                case "--porto":
                    portoTcp = Integer.parseInt(args[++i]);
                    break;
                case "--rmi":
                    portoRmi = Integer.parseInt(args[++i]);
                    break;
                case "--grupo":
                    grupoMcast = args[++i];
                    break;
                case "--udp":
                    portoUdp = Integer.parseInt(args[++i]);
                    break;
                default:
                    System.err.println("Opcao desconhecida: " + args[i]);
                    imprimirUso();
                    System.exit(1);
            }
        }

        String sufixo = Long.toString(System.nanoTime() % 100_000);
        switch (cenario) {
            case "aviso-perdido":
                cenarioAvisoPerdido(sufixo);
                break;
            case "fecho":
                cenarioFechoAutomatico(sufixo);
                break;
            case "cancelamento":
                cenarioCancelamentoRmi(sufixo);
                break;
            case "anti-sniping":
                cenarioAntiSniping(sufixo);
                break;
            case "reinicio-prep":
                cenarioPrepararReinicio(sufixo);
                break;
            case "concorrencia":
                cenarioConcorrencia(sufixo);
                break;
            default:
                System.err.println("Cenario desconhecido: " + cenario);
                imprimirUso();
                System.exit(1);
        }
    }

    private static void cenarioAvisoPerdido(String suf) throws Exception {
        System.out.println("=== Cenario: Aviso Multicast Perdido e Recuperacao por TCP (I2) ===");
        String dono = "dono_" + suf;
        String lic = "lic_" + suf;
        registar(dono);
        registar(lic);

        try (Ligacao d = new Ligacao(dono); Ligacao l = new Ligacao(lic)) {
            int id = criarLeilao(d, 100, 10, 120, "Camara Retro " + suf);
            System.out.println("Leilao #" + id + " criado. A iniciar ouvinte multicast com descarte 1 em 2...");

            AtomicBoolean ativo = new AtomicBoolean(true);
            List<String> eventos = new CopyOnWriteArrayList<>();

            try (MulticastSocket ms = new MulticastSocket(portoUdp)) {
                InetAddress g = InetAddress.getByName(grupoMcast);
                ms.joinGroup(new InetSocketAddress(g, portoUdp), null);

                Thread t = new Thread(() -> {
                    int ultimoSeq = 0;
                    int contador = 0;
                    byte[] buf = new byte[2048];
                    while (ativo.get() && !ms.isClosed()) {
                        try {
                            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                            ms.receive(pkt);
                            String msg = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8).trim();
                            if (!msg.startsWith("AVISO ")) {
                                continue;
                            }
                            Map<String, String> c = Mensagens.parseChaveValor(msg.substring(6));
                            if (Integer.parseInt(c.get("id")) != id) {
                                continue;
                            }
                            int seq = Integer.parseInt(c.get("seq"));
                            contador++;
                            if (contador % 2 == 0) {
                                String log = "[SIMULACAO] Descartado aviso seq=" + seq + " (" + msg + ")";
                                System.out.println(log);
                                eventos.add("DESCARTADO:" + seq);
                                continue;
                            }
                            if (seq > ultimoSeq + 1) {
                                System.out.println("[PERDA DETETADA] Esperava seq=" + (ultimoSeq + 1)
                                        + ", chegou seq=" + seq + ". A recuperar por TCP ESTADO " + id + "...");
                                String est = pedirSimples("ESTADO " + id);
                                System.out.println("[RECUPERADO TCP] " + est);
                                Map<String, String> eMap = Mensagens.parseChaveValor(est.substring(3));
                                ultimoSeq = Integer.parseInt(eMap.get("seq"));
                                eventos.add("RECUPERADO:" + ultimoSeq);
                            } else if (seq == ultimoSeq + 1) {
                                ultimoSeq = seq;
                                System.out.println("[TEMPO REAL] " + msg);
                                eventos.add("OK:" + seq);
                            }
                        } catch (IOException ignored) {
                        }
                    }
                });
                t.setDaemon(true);
                t.start();

                Thread.sleep(150);
                System.out.println("Licitacao 1 (100): " + l.pedir("LICITAR " + id + " 100"));
                Thread.sleep(150);
                System.out.println("Licitacao 2 (110 - sera descartada): " + l.pedir("LICITAR " + id + " 110"));
                Thread.sleep(150);
                System.out.println("Licitacao 3 (120 - dispara recuperacao): " + l.pedir("LICITAR " + id + " 120"));
                Thread.sleep(300);
                ativo.set(false);
                ms.leaveGroup(new InetSocketAddress(g, portoUdp), null);
            }
            System.out.println("Resumo eventos: " + eventos);
        }
    }

    private static void cenarioFechoAutomatico(String suf) throws Exception {
        System.out.println("=== Cenario: Fecho Automatico pelo Relogio do Servidor (I3) ===");
        String dono = "dono_" + suf;
        registar(dono);

        try (Ligacao d = new Ligacao(dono);
             MulticastSocket ms = new MulticastSocket(portoUdp)) {

            InetAddress g = InetAddress.getByName(grupoMcast);
            ms.joinGroup(new InetSocketAddress(g, portoUdp), null);
            ms.setSoTimeout(6000);

            int id = criarLeilao(d, 50, 5, 3, "Relogio Rapido " + suf);
            System.out.println("Criado leilao #" + id + " com duracao de 3 s (sem licitacoes tardias para nao ativar anti-sniping).");
            System.out.println("Estado inicial: " + pedirSimples("CONSULTAR " + id));
            System.out.println("A aguardar fecho automatico pelo servidor...");

            long t0 = System.currentTimeMillis();
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() - t0 < 5500) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                ms.receive(pkt);
                String msg = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8).trim();
                if (msg.contains("id=" + id + " ") && msg.contains("tipo=FECHO")) {
                    long dt = System.currentTimeMillis() - t0;
                    System.out.println("[MULTICAST FECHO recebido em " + dt + " ms] " + msg);
                    break;
                }
            }
            System.out.println("Estado apos fecho: " + pedirSimples("CONSULTAR " + id));
            ms.leaveGroup(new InetSocketAddress(g, portoUdp), null);
        }
    }

    private static void cenarioCancelamentoRmi(String suf) throws Exception {
        System.out.println("=== Cenario: Cancelamento via Consola Java RMI (C1) ===");
        String dono = "dono_" + suf;
        String lic = "lic_" + suf;
        registar(dono);
        registar(lic);

        try (Ligacao d = new Ligacao(dono);
             Ligacao l = new Ligacao(lic);
             MulticastSocket ms = new MulticastSocket(portoUdp)) {

            InetAddress g = InetAddress.getByName(grupoMcast);
            ms.joinGroup(new InetSocketAddress(g, portoUdp), null);
            ms.setSoTimeout(4000);

            int id = criarLeilao(d, 200, 10, 120, "Obra Arte " + suf);
            System.out.println("Licitacao valida: " + l.pedir("LICITAR " + id + " 200"));
            System.out.println("Antes do cancelamento: " + pedirSimples("CONSULTAR " + id));

            AdminRMI admin = (AdminRMI) LocateRegistry.getRegistry(host, portoRmi).lookup("LeiloesAdmin");
            System.out.println("Utilizadores online (RMI): " + admin.listarUtilizadoresOnline());
            System.out.println("RMI cancelarLeilao(" + id + ") -> " + admin.cancelarLeilao(id));

            byte[] buf = new byte[2048];
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 3000) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                ms.receive(pkt);
                String msg = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8).trim();
                if (msg.contains("id=" + id + " ") && msg.contains("tipo=CANCELADO")) {
                    System.out.println("[MULTICAST CANCELADO] " + msg);
                    break;
                }
            }

            System.out.println("Apos cancelamento: " + pedirSimples("CONSULTAR " + id));
            System.out.println("Tentativa de licitar apos cancelamento: " + l.pedir("LICITAR " + id + " 250"));
            ms.leaveGroup(new InetSocketAddress(g, portoUdp), null);
        }
    }

    private static void cenarioAntiSniping(String suf) throws Exception {
        System.out.println("=== Cenario: Anti-sniping (C2) ===");
        String dono = "dono_" + suf;
        String lic = "lic_" + suf;
        registar(dono);
        registar(lic);

        try (Ligacao d = new Ligacao(dono);
             Ligacao l = new Ligacao(lic);
             MulticastSocket ms = new MulticastSocket(portoUdp)) {

            InetAddress g = InetAddress.getByName(grupoMcast);
            ms.joinGroup(new InetSocketAddress(g, portoUdp), null);
            ms.setSoTimeout(4000);

            int id = criarLeilao(d, 100, 5, 8, "Guitarra Vintage " + suf);
            Thread.sleep(2100);
            System.out.println("Antes de licitar (~6s restantes): " + pedirSimples("CONSULTAR " + id));
            System.out.println("Licitacao nos ultimos segundos: " + l.pedir("LICITAR " + id + " 100"));

            byte[] buf = new byte[2048];
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 3000) {
                DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                ms.receive(pkt);
                String msg = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(), StandardCharsets.UTF_8).trim();
                if (msg.contains("id=" + id + " ") && msg.contains("tipo=LICITACAO")) {
                    System.out.println("[MULTICAST ANTI-SNIPING] " + msg);
                    break;
                }
            }
            System.out.println("Apos licitar (fecho adiado para ~30s): " + pedirSimples("CONSULTAR " + id));
            ms.leaveGroup(new InetSocketAddress(g, portoUdp), null);
        }
    }

    private static void cenarioPrepararReinicio(String suf) throws Exception {
        System.out.println("=== Cenario: Preparar Estado para Teste de Reinicio (B7) ===");
        String dono = "dono_" + suf;
        String lic = "lic_" + suf;
        registar(dono);
        registar(lic);

        try (Ligacao d = new Ligacao(dono); Ligacao l = new Ligacao(lic)) {
            int id = criarLeilao(d, 500, 25, 300, "Portatil Pro " + suf);
            System.out.println("Licitacao 1: " + l.pedir("LICITAR " + id + " 500"));
            System.out.println("Licitacao 2: " + l.pedir("LICITAR " + id + " 550"));
            System.out.println("Estado antes do reinicio:");
            System.out.println("  " + pedirSimples("CONSULTAR " + id));
            System.out.println("\nAgora pare o Servidor (Ctrl+C), volte a arrancar, e corra no cliente:");
            System.out.println("  CONSULTAR " + id);
        }
    }

    private static void cenarioConcorrencia(String suf) throws Exception {
        System.out.println("=== Cenario: Stress Test de Concorrencia (B5 / G1) ===");
        String dono = "dono_" + suf;
        registar(dono);
        int id;
        try (Ligacao d = new Ligacao(dono)) {
            id = criarLeilao(d, 1, 1, 120, "Teste Concorrencia " + suf);
        }

        int numThreads = 20;
        List<String> users = new ArrayList<>();
        for (int i = 0; i < numThreads; i++) {
            String u = "u" + i + "_" + suf;
            registar(u);
            users.add(u);
        }

        CountDownLatch pronto = new CountDownLatch(numThreads);
        CountDownLatch arrancar = new CountDownLatch(1);
        CountDownLatch fim = new CountDownLatch(numThreads);
        List<Integer> ordensAceites = new CopyOnWriteArrayList<>();

        for (int i = 0; i < numThreads; i++) {
            final String u = users.get(i);
            final int valor = 100 + i;
            new Thread(() -> {
                try (Ligacao l = new Ligacao(u)) {
                    pronto.countDown();
                    arrancar.await();
                    for (int tentativa = 0; tentativa < 5; tentativa++) {
                        String r = l.pedir("LICITAR " + id + " " + (valor + tentativa * 30));
                        if (r.startsWith("OK n=")) {
                            ordensAceites.add(Integer.parseInt(r.substring(5)));
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Erro thread concorrente: " + e.getMessage());
                } finally {
                    fim.countDown();
                }
            }).start();
        }

        pronto.await(10, TimeUnit.SECONDS);
        arrancar.countDown();
        fim.await(15, TimeUnit.SECONDS);

        String estadoFinal = pedirSimples("CONSULTAR " + id);
        Map<String, String> campos = Mensagens.parseChaveValor(estadoFinal.substring(3));
        int licitacoesReportadas = Integer.parseInt(campos.get("licitacoes"));
        long unicas = ordensAceites.stream().distinct().count();

        System.out.println("Estado final: " + estadoFinal);
        System.out.println("Ordens recebidas=" + ordensAceites.size()
                + ", unicas=" + unicas
                + ", licitacoes no servidor=" + licitacoesReportadas);
        if (ordensAceites.size() != unicas || ordensAceites.size() != licitacoesReportadas) {
            throw new AssertionError("FALHA DE CONCORRENCIA! Houve sobreposicao ou perda de licitacoes.");
        }
        System.out.println("PASSA: 0 perdas, 0 sobreposicoes!");
    }

    private static void registar(String u) throws IOException {
        String r = pedirSimples("REGISTAR " + u + " segredo1");
        if (!"OK".equals(r)) {
            throw new IOException("REGISTAR falhou: " + r);
        }
    }

    private static int criarLeilao(Ligacao l, int base, int inc, int dur, String art) throws IOException {
        String r = l.pedir("CRIAR " + base + " " + inc + " " + dur + " " + art);
        if (!r.startsWith("OK id=")) {
            throw new IOException("CRIAR falhou: " + r);
        }
        return Integer.parseInt(r.substring(6));
    }

    private static String pedirSimples(String cmd) throws IOException {
        try (Socket s = new Socket(host, portoTcp);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = s.getOutputStream()) {
            s.setSoTimeout(5000);
            out.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            String resp = in.readLine();
            out.write("SAIR\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            return resp;
        }
    }

    private static final class Ligacao implements AutoCloseable {
        final Socket s;
        final BufferedReader in;
        final OutputStream out;

        Ligacao(String user) throws IOException {
            s = new Socket(host, portoTcp);
            s.setSoTimeout(5000);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = s.getOutputStream();
            String r = pedir("LOGIN " + user + " segredo1");
            if (!"OK".equals(r)) {
                throw new IOException("LOGIN falhou para " + user + ": " + r);
            }
        }

        String pedir(String cmd) throws IOException {
            out.write((cmd + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return in.readLine();
        }

        @Override
        public void close() throws IOException {
            try {
                pedir("SAIR");
            } catch (IOException ignored) {
            }
            s.close();
        }
    }

    private static void imprimirUso() {
        System.out.println("Uso: java -Djava.net.preferIPv4Stack=true -cp out demo.DemoCenarios <cenario> [opcoes]");
        System.out.println("Cenarios: aviso-perdido | fecho | cancelamento | anti-sniping | reinicio-prep | concorrencia");
    }
}

