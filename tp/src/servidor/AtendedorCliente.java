package servidor;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Thread responsavel por atender uma ligacao TCP de um cliente, cumprindo
 * integralmente a gramatica e a ordem de validacao do Anexo A.
 */
public class AtendedorCliente implements Runnable {
    private static final int MAX_CHARS_LINHA = 1024;
    private static final int MAX_BYTES_BUFFER = MAX_CHARS_LINHA * 4;

    private final Socket socket;
    private final EstadoServidor estado;
    private final Set<Socket> socketsAtivos;
    private String utilizadorAtual;

    public AtendedorCliente(Socket socket, EstadoServidor estado, Set<Socket> socketsAtivos) {
        this.socket = socket;
        this.estado = estado;
        this.socketsAtivos = socketsAtivos;
    }

    @Override
    public void run() {
        try (Socket s = this.socket) {
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            while (true) {
                LinhaLida linhaLida = lerLinha(in);
                if (linhaLida == null) {
                    break;
                }

                if (linhaLida.demasiadoLonga) {
                    enviarLinha(out, "ERRO SINTAXE linha excede 1024 caracteres");
                    out.flush();
                    continue;
                }

                String linha = linhaLida.texto;
                if (linha.isEmpty() || linha.startsWith(" ")) {
                    enviarLinha(out, "ERRO SINTAXE formato de linha invalido");
                    out.flush();
                    continue;
                }

                int primeiroEspaco = linha.indexOf(' ');
                String comando = (primeiroEspaco == -1) ? linha : linha.substring(0, primeiroEspaco);

                if ("LISTAR".equals(comando)) {
                    if (!"LISTAR".equals(linha)) {
                        enviarLinha(out, "ERRO SINTAXE LISTAR nao aceita argumentos");
                    } else {
                        List<String> linhas = estado.listar();
                        for (String l : linhas) {
                            enviarLinha(out, l);
                        }
                    }
                    out.flush();
                } else if ("SAIR".equals(comando)) {
                    if (!"SAIR".equals(linha)) {
                        enviarLinha(out, "ERRO SINTAXE SAIR nao aceita argumentos");
                        out.flush();
                    } else {
                        estado.terminarSessao(utilizadorAtual);
                        utilizadorAtual = null;
                        enviarLinha(out, "OK");
                        out.flush();
                        break;
                    }
                } else {
                    String resposta = processarComandoSimples(comando, linha);
                    enviarLinha(out, resposta);
                    out.flush();
                }
            }
        } catch (IOException e) {
            // Ligacao terminada pelo cliente ou erro de rede; sessao e limpa no finally
        } finally {
            estado.terminarSessao(utilizadorAtual);
            utilizadorAtual = null;
            socketsAtivos.remove(socket);
        }
    }

    private String processarComandoSimples(String comando, String linha) {
        switch (comando) {
            case "REGISTAR": {
                if (linha.endsWith(" ")) {
                    return "ERRO SINTAXE espaco no fim da linha";
                }
                String[] p = linha.split(" ", -1);
                if (p.length != 3 || p[1].isEmpty() || p[2].isEmpty()) {
                    return "ERRO SINTAXE uso: REGISTAR <utilizador> <palavra-passe>";
                }
                if (!utilizadorValido(p[1]) || !passwordValida(p[2])) {
                    return "ERRO VALOR utilizador ou palavra-passe invalidos";
                }
                return estado.registar(p[1], p[2]);
            }
            case "LOGIN": {
                if (linha.endsWith(" ")) {
                    return "ERRO SINTAXE espaco no fim da linha";
                }
                String[] p = linha.split(" ", -1);
                if (p.length != 3 || p[1].isEmpty() || p[2].isEmpty()) {
                    return "ERRO SINTAXE uso: LOGIN <utilizador> <palavra-passe>";
                }
                if (utilizadorAtual != null) {
                    return "ERRO JA_AUTENTICADO sessao ja iniciada";
                }
                String res = estado.login(p[1], p[2]);
                if ("OK".equals(res)) {
                    utilizadorAtual = p[1];
                }
                return res;
            }
            case "CRIAR": {
                String[] p = linha.split(" ", 5);
                if (p.length != 5 || p[1].isEmpty() || p[2].isEmpty() || p[3].isEmpty()) {
                    return "ERRO SINTAXE uso: CRIAR <base> <incremento> <duracao> <artigo>";
                }
                if (utilizadorAtual == null) {
                    return "ERRO SEM_SESSAO faca LOGIN primeiro";
                }
                Integer base = parseInteiro(p[1], 0, 999_999_999);
                Integer inc = parseInteiro(p[2], 1, 999_999_999);
                Integer dur = parseInteiro(p[3], 1, 86_400);
                if (base == null || inc == null || dur == null || !artigoValido(p[4])) {
                    return "ERRO VALOR campos de criacao invalidos";
                }
                return estado.criarLeilao(utilizadorAtual, base, inc, dur, p[4]);
            }
            case "CONSULTAR": {
                if (linha.endsWith(" ")) {
                    return "ERRO SINTAXE espaco no fim da linha";
                }
                String[] p = linha.split(" ", -1);
                if (p.length != 2 || p[1].isEmpty()) {
                    return "ERRO SINTAXE uso: CONSULTAR <id>";
                }
                Integer id = parseInteiro(p[1], 1, 999_999_999);
                if (id == null) {
                    return "ERRO VALOR id invalido";
                }
                return estado.consultar(id);
            }
            case "ESTADO": {
                if (linha.endsWith(" ")) {
                    return "ERRO SINTAXE espaco no fim da linha";
                }
                String[] p = linha.split(" ", -1);
                if (p.length != 2 || p[1].isEmpty()) {
                    return "ERRO SINTAXE uso: ESTADO <id>";
                }
                Integer id = parseInteiro(p[1], 1, 999_999_999);
                if (id == null) {
                    return "ERRO VALOR id invalido";
                }
                return estado.estadoEstendido(id);
            }
            case "LICITAR": {
                if (linha.endsWith(" ")) {
                    return "ERRO SINTAXE espaco no fim da linha";
                }
                String[] p = linha.split(" ", -1);
                if (p.length != 3 || p[1].isEmpty() || p[2].isEmpty()) {
                    return "ERRO SINTAXE uso: LICITAR <id> <valor>";
                }
                if (utilizadorAtual == null) {
                    return "ERRO SEM_SESSAO faca LOGIN primeiro";
                }
                Integer id = parseInteiro(p[1], 1, 999_999_999);
                Integer val = parseInteiro(p[2], 0, 999_999_999);
                if (id == null || val == null) {
                    return "ERRO VALOR id ou valor invalidos";
                }
                return estado.licitar(utilizadorAtual, id, val);
            }
            default:
                return "ERRO SINTAXE comando desconhecido";
        }
    }

    private static void enviarLinha(OutputStream out, String texto) throws IOException {
        out.write((texto + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static LinhaLida lerLinha(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        boolean excedeuLimiteBytes = false;
        boolean leuAlgumByte = false;

        while (true) {
            int b = in.read();
            if (b == -1) {
                return null;
            }
            leuAlgumByte = true;
            if (b == '\n') {
                break;
            }
            if (!excedeuLimiteBytes) {
                if (buffer.size() < MAX_BYTES_BUFFER) {
                    buffer.write(b);
                } else {
                    excedeuLimiteBytes = true;
                }
            }
        }

        if (!leuAlgumByte) {
            return null;
        }
        if (excedeuLimiteBytes) {
            return new LinhaLida("", true);
        }

        byte[] raw = buffer.toByteArray();
        int len = raw.length;
        if (len > 0 && raw[len - 1] == '\r') {
            len--;
        }
        String texto = new String(raw, 0, len, StandardCharsets.UTF_8);
        if (texto.length() > MAX_CHARS_LINHA) {
            return new LinhaLida("", true);
        }
        return new LinhaLida(texto, false);
    }

    private static boolean utilizadorValido(String u) {
        return u.matches("[A-Za-z0-9_]{3,20}");
    }

    private static boolean passwordValida(String pw) {
        if (pw.isEmpty() || pw.length() > 64) {
            return false;
        }
        for (int i = 0; i < pw.length(); i++) {
            char c = pw.charAt(i);
            if (c == ' ' || Character.isISOControl(c) || Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }

    private static boolean artigoValido(String art) {
        if (art.isEmpty() || art.length() > 60) {
            return false;
        }
        if (art.startsWith(" ") || art.endsWith(" ")) {
            return false;
        }
        for (int i = 0; i < art.length(); i++) {
            if (Character.isISOControl(art.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static Integer parseInteiro(String s, int min, int max) {
        if (!s.matches("[0-9]{1,9}")) {
            return null;
        }
        long v = Long.parseLong(s);
        if (v < min || v > max) {
            return null;
        }
        return (int) v;
    }

    private static final class LinhaLida {
        final String texto;
        final boolean demasiadoLonga;

        LinhaLida(String texto, boolean demasiadoLonga) {
            this.texto = texto;
            this.demasiadoLonga = demasiadoLonga;
        }
    }
}

