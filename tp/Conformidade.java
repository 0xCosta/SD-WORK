import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * VERIFICADOR DE CONFORMIDADE com o protocolo do Anexo A (Trabalho Pratico SD 2026/27).
 * Material para os alunos: o docente usa exatamente o mesmo programa para pontuar o criterio G2.
 *
 * O que faz: liga-se ao SEU servidor (host e porto), executa uma sequencia de comandos do
 * Anexo A, incluindo erros e casos de fronteira, e escreve PASSA ou FALHA por caso. No fim
 * mostra um resumo: [N] = casos do caminho normal, [E] = casos de erro e de fronteira.
 *
 * Como usar (com o servidor ja a correr):
 *   javac --release 17 -encoding UTF-8 Conformidade.java
 *   java Conformidade localhost 5050        (host e porto sao opcionais; por omissao localhost 5050)
 *
 * Saida: codigo 0 se todos os casos PASSA, 1 se algum FALHA. Cada FALHA diz o pedido enviado,
 * o que se esperava e o que o servidor respondeu. Demora cerca de 4 segundos (inclui o fecho
 * real de um leilao de 1 s). Se o servidor nunca responder (nem ao primeiro pedido, 5 s), o programa termina logo com uma mensagem (codigo 2).
 *
 * Atencao:
 *  - Cria utilizadores e leiloes com nomes unicos em cada execucao: fica lixo no historico do
 *    servidor (apague o ficheiro de historico entre execucoes, se quiser comecar de novo).
 *  - Nao testa concorrencia, persistencia, multicast nem as vossas extensoes ao protocolo.
 *  - Passar todos os casos nao prova que o servidor esta certo; so que cumpre o Anexo A nestes casos.
 *  - Na consola do Windows os acentos de mensagens de erro podem sair trocados (consola nao UTF-8);
 *    o programa envia e compara os dados sempre em UTF-8.
 */
public class Conformidade {

    static String host = "localhost";
    static int porto = 5050;
    static final String SUF = Long.toString(System.nanoTime() % 1_000_000);
    static final String DONO = "dono" + SUF;
    static final String LIC = "lic" + SUF;
    static final String PW = "segredo1";

    static boolean respondeu;   // ja houve alguma resposta do servidor?
    static int passaN;
    static int totalN;
    static int passaE;
    static int totalE;
    static final List<String> falhas = new ArrayList<>();

    static class Falha extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Falha(String m) {
            super(m);
        }
    }

    /** Uma ligacao ao servidor. */
    static class L implements AutoCloseable {
        final Socket s;
        final BufferedReader in;
        final OutputStream out;

        L() throws IOException {
            s = new Socket(host, porto);
            s.setSoTimeout(5000);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = s.getOutputStream();
        }

        void bruto(String texto) throws IOException {
            out.write(texto.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        String linha() throws IOException {
            try {
                String r = in.readLine();
                respondeu = true;
                return r;
            } catch (SocketTimeoutException e) {
                if (!respondeu) {   // servidor mudo desde o inicio: nao vale a pena continuar
                    System.out.println("O servidor em " + host + ":" + porto + " nao responde (5 s sem resposta). A terminar.");
                    System.exit(2);
                }
                throw new Falha("sem resposta em 5 s");
            }
        }

        String pedir(String pedido) throws IOException {
            bruto(pedido + "\n");
            return linha();
        }

        L entrar(String u) throws IOException {
            exigir(pedir("LOGIN " + u + " " + PW).equals("OK"), "LOGIN de " + u + " nao deu OK");
            return this;
        }

        @Override
        public void close() throws IOException {
            s.close();
        }
    }

    static void exigir(boolean ok, String msg) {
        if (!ok) {
            throw new Falha(msg);
        }
    }

    static void erro(String resposta, String codigo, String pedido) {
        exigir(resposta != null && resposta.matches("ERRO " + codigo + " \\S.*"),
                "pedido [" + abreviar(pedido) + "] esperava ERRO " + codigo + " <motivo>, recebeu [" + abreviar(resposta) + "]");
    }

    static String abreviar(String t) {
        return t == null ? "EOF" : t.length() > 60 ? t.substring(0, 60) + "..." : t;
    }

    interface Corpo {
        void correr() throws Exception;
    }

    static void caso(char tipo, String nome, Corpo c) {
        boolean ok = true;
        String motivo = "";
        try {
            c.correr();
        } catch (Falha f) {
            ok = false;
            motivo = f.getMessage();
        } catch (Exception e) {
            ok = false;
            motivo = e.toString();
        }
        if (tipo == 'N') {
            totalN++;
            passaN += ok ? 1 : 0;
        } else {
            totalE++;
            passaE += ok ? 1 : 0;
        }
        System.out.println((ok ? "PASSA " : "FALHA ") + "[" + tipo + "] " + nome + (ok ? "" : "  <- " + motivo));
        if (!ok) {
            falhas.add(nome);
        }
    }

    /** Verifica um registo de leilao com os campos por ordem fixa e devolve o valor de cada campo pelo nome. */
    static java.util.Map<String, String> registo(String texto, String prefixo) {
        exigir(texto != null && texto.startsWith(prefixo), "esperava prefixo [" + prefixo + "] em [" + abreviar(texto) + "]");
        String r = texto.substring(prefixo.length());
        String[] nomes = {"id", "estado", "melhor", "vencedor", "criador", "base", "incremento", "licitacoes", "restante", "artigo"};
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < nomes.length; i++) {
            String k = nomes[i] + "=";
            exigir(r.startsWith(k), "campo " + nomes[i] + " em falta ou fora de ordem em [" + abreviar(texto) + "]");
            r = r.substring(k.length());
            if (i < nomes.length - 1) {
                int sp = r.indexOf(' ');
                exigir(sp > 0, "registo truncado em [" + abreviar(texto) + "]");
                m.put(nomes[i], r.substring(0, sp));
                r = r.substring(sp + 1);
            } else {
                m.put(nomes[i], r);   // o artigo e o resto da linha
            }
        }
        return m;
    }

    static int criar(L l, String args) throws IOException {
        String r = l.pedir("CRIAR " + args);
        exigir(r != null && r.matches("OK id=[1-9][0-9]*"), "CRIAR " + args + " devia dar OK id=<n>, deu [" + abreviar(r) + "]");
        return Integer.parseInt(r.substring(6));
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 1) {
            host = args[0];
        }
        if (args.length >= 2) {
            porto = Integer.parseInt(args[1]);
        }
        final int[] id = new int[3];

        caso('N', "REGISTAR de dois utilizadores novos", () -> {
            try (L l = new L()) {
                exigir(l.pedir("REGISTAR " + DONO + " " + PW).equals("OK"), "REGISTAR " + DONO);
                exigir(l.pedir("REGISTAR " + LIC + " " + PW).equals("OK"), "REGISTAR " + LIC);
            }
        });
        caso('E', "REGISTAR repetido -> ERRO EXISTE", () -> {
            try (L l = new L()) {
                erro(l.pedir("REGISTAR " + DONO + " outra"), "EXISTE", "REGISTAR repetido");
            }
        });
        caso('E', "REGISTAR com utilizador curto ou com caracteres ilegais -> ERRO VALOR", () -> {
            try (L l = new L()) {
                erro(l.pedir("REGISTAR ab " + PW), "VALOR", "REGISTAR ab");
                erro(l.pedir("REGISTAR a!b" + SUF + " " + PW), "VALOR", "REGISTAR a!b");
            }
        });
        caso('E', "REGISTAR sem palavra-passe -> ERRO SINTAXE", () -> {
            try (L l = new L()) {
                erro(l.pedir("REGISTAR so" + SUF), "SINTAXE", "REGISTAR so");
            }
        });
        caso('E', "LOGIN inexistente ou com palavra-passe errada -> ERRO CREDENCIAIS", () -> {
            try (L l = new L()) {
                erro(l.pedir("LOGIN naoexiste" + SUF + " " + PW), "CREDENCIAIS", "LOGIN inexistente");
                erro(l.pedir("LOGIN " + DONO + " errada"), "CREDENCIAIS", "LOGIN errada");
            }
        });
        caso('N', "LOGIN correto -> OK", () -> {
            try (L l = new L()) {
                l.entrar(DONO);
            }
        });
        caso('E', "LOGIN com sessao iniciada -> ERRO JA_AUTENTICADO e a sessao mantem-se", () -> {
            try (L l = new L()) {
                l.entrar(DONO);
                erro(l.pedir("LOGIN " + LIC + " " + PW), "JA_AUTENTICADO", "LOGIN 2");
                int n = criar(l, "10 1 60 teste");   // continua autenticado como DONO
                exigir(n > 0, "sessao perdida");
            }
        });
        caso('E', "comando desconhecido, em minusculas ou linha vazia -> ERRO SINTAXE", () -> {
            try (L l = new L()) {
                erro(l.pedir("FOO bar"), "SINTAXE", "FOO bar");
                erro(l.pedir("listar"), "SINTAXE", "listar");
                erro(l.pedir(""), "SINTAXE", "(linha vazia)");
            }
        });
        caso('E', "espacos a mais ou no fim -> ERRO SINTAXE", () -> {
            try (L l = new L()) {
                erro(l.pedir("LISTAR "), "SINTAXE", "LISTAR_");
                erro(l.pedir(" LISTAR"), "SINTAXE", "_LISTAR");
                erro(l.pedir("CONSULTAR  1"), "SINTAXE", "CONSULTAR__1");
            }
        });
        caso('E', "linha com mais de 1024 caracteres -> ERRO SINTAXE e a ligacao continua", () -> {
            try (L l = new L()) {
                erro(l.pedir("REGISTAR " + "a".repeat(5000) + " " + PW), "SINTAXE", "REGISTAR com campo de 5000 caracteres");
                String r = l.pedir("LISTAR");
                exigir(r != null && r.matches("OK [0-9]+"), "LISTAR apos linha longa deu [" + abreviar(r) + "]");
                int n = Integer.parseInt(r.substring(3));
                for (int i = 0; i < n; i++) {
                    l.linha();
                }
            }
        });
        caso('N', "terminador \\r\\n tambem e aceite", () -> {
            try (L l = new L()) {
                l.bruto("LISTAR\r\n");
                String r = l.linha();
                exigir(r != null && r.matches("OK [0-9]+"), "LISTAR com CRLF deu [" + abreviar(r) + "]");
            }
        });
        caso('E', "sem sessao: CRIAR e LICITAR -> ERRO SEM_SESSAO (antes de VALOR e de INEXISTENTE)", () -> {
            try (L l = new L()) {
                erro(l.pedir("CRIAR 100 5 60 x"), "SEM_SESSAO", "CRIAR sem login");
                erro(l.pedir("CRIAR abc 5 60 x"), "SEM_SESSAO", "CRIAR abc sem login");
                erro(l.pedir("LICITAR 999999999 5"), "SEM_SESSAO", "LICITAR sem login");
            }
        });
        caso('E', "CRIAR com campos em falta -> ERRO SINTAXE", () -> {
            try (L l = new L().entrar(DONO)) {
                erro(l.pedir("CRIAR 100 5 60"), "SINTAXE", "CRIAR sem artigo");
                erro(l.pedir("CRIAR 100 5"), "SINTAXE", "CRIAR curto");
            }
        });
        caso('E', "CRIAR com valores fora das regras -> ERRO VALOR", () -> {
            try (L l = new L().entrar(DONO)) {
                String[] maus = {"abc 5 60 x", "100 0 60 x", "100 5 0 x", "100 5 86401 x", "-5 5 60 x",
                    "1000000000 5 60 x", "100 5 60  x", "100 5 60 x ", "100 5 60 ", "100 5 60 " + "a".repeat(61)};
                for (String m : maus) {
                    erro(l.pedir("CRIAR " + m), "VALOR", "CRIAR " + m);
                }
            }
        });
        caso('N', "CRIAR com artigo com espacos e acentos -> OK id=<n>", () -> {
            try (L l = new L().entrar(DONO)) {
                id[0] = criar(l, "100 5 60 Relógio de bolso");
            }
        });
        caso('N', "LISTAR sem sessao: OK <n> e n linhas LEILAO, com o leilao criado", () -> {
            try (L l = new L()) {
                String r = l.pedir("LISTAR");
                exigir(r != null && r.matches("OK [0-9]+"), "LISTAR deu [" + abreviar(r) + "]");
                int n = Integer.parseInt(r.substring(3));
                boolean achou = false;
                for (int i = 0; i < n; i++) {
                    java.util.Map<String, String> m = registo(l.linha(), "LEILAO ");
                    if (m.get("id").equals(Integer.toString(id[0]))) {
                        achou = m.get("artigo").equals("Relógio de bolso") && m.get("criador").equals(DONO);
                    }
                }
                exigir(achou, "o leilao " + id[0] + " nao esta na lista com artigo e criador certos");
            }
        });
        caso('N', "CONSULTAR de leilao novo: campos por ordem, estado ABERTO, sem licitacoes", () -> {
            try (L l = new L()) {
                java.util.Map<String, String> m = registo(l.pedir("CONSULTAR " + id[0]), "OK ");
                exigir(m.get("id").equals(Integer.toString(id[0])) && m.get("estado").equals("ABERTO")
                        && m.get("melhor").equals("0") && m.get("vencedor").equals("-") && m.get("criador").equals(DONO)
                        && m.get("base").equals("100") && m.get("incremento").equals("5") && m.get("licitacoes").equals("0")
                        && m.get("artigo").equals("Relógio de bolso"), "valores errados em " + m);
                int rest = Integer.parseInt(m.get("restante"));
                exigir(rest >= 1 && rest <= 60, "restante fora de 1..60: " + rest);
            }
        });
        caso('E', "CONSULTAR inexistente -> INEXISTENTE; id invalido -> VALOR; sem id -> SINTAXE", () -> {
            try (L l = new L()) {
                erro(l.pedir("CONSULTAR 999999999"), "INEXISTENTE", "CONSULTAR 999999999");
                erro(l.pedir("CONSULTAR abc"), "VALOR", "CONSULTAR abc");
                erro(l.pedir("CONSULTAR 0"), "VALOR", "CONSULTAR 0");
                erro(l.pedir("CONSULTAR"), "SINTAXE", "CONSULTAR");
            }
        });
        caso('E', "o criador licita no seu leilao -> ERRO PROPRIO", () -> {
            try (L l = new L().entrar(DONO)) {
                erro(l.pedir("LICITAR " + id[0] + " 100"), "PROPRIO", "LICITAR do criador");
            }
        });
        caso('N', "licitacoes validas: base, subida minima, o vencedor volta a licitar; OK n=1,2,3", () -> {
            try (L l = new L().entrar(LIC)) {
                exigir(l.pedir("LICITAR " + id[0] + " 100").equals("OK n=1"), "1.a licitacao (= base) devia dar OK n=1");
                erro(l.pedir("LICITAR " + id[0] + " 104"), "BAIXA", "LICITAR 104 (minimo 105)");
                exigir(l.pedir("LICITAR " + id[0] + " 105").equals("OK n=2"), "105 devia dar OK n=2");
                erro(l.pedir("LICITAR " + id[0] + " 105"), "BAIXA", "LICITAR 105 repetido");
                exigir(l.pedir("LICITAR " + id[0] + " 110").equals("OK n=3"), "110 (o vencedor volta a licitar) devia dar OK n=3");
            }
        });
        caso('N', "CONSULTAR reflete as licitacoes: melhor 110, vencedor, licitacoes 3", () -> {
            try (L l = new L()) {
                java.util.Map<String, String> m = registo(l.pedir("CONSULTAR " + id[0]), "OK ");
                exigir(m.get("melhor").equals("110") && m.get("vencedor").equals(LIC) && m.get("licitacoes").equals("3")
                        && m.get("estado").equals("ABERTO"), "valores errados em " + m);
            }
        });
        caso('E', "primeira licitacao abaixo da base -> ERRO BAIXA e nada muda", () -> {
            try (L d = new L().entrar(DONO); L l = new L().entrar(LIC)) {
                id[1] = criar(d, "100 5 60 outro");
                erro(l.pedir("LICITAR " + id[1] + " 99"), "BAIXA", "LICITAR 99 (base 100)");
                exigir(registo(l.pedir("CONSULTAR " + id[1]), "OK ").get("licitacoes").equals("0"), "licitacoes devia ser 0");
            }
        });
        caso('E', "LICITAR com argumentos invalidos -> VALOR / SINTAXE / INEXISTENTE", () -> {
            try (L l = new L().entrar(LIC)) {
                erro(l.pedir("LICITAR " + id[1] + " abc"), "VALOR", "LICITAR valor abc");
                erro(l.pedir("LICITAR " + id[1] + " -5"), "VALOR", "LICITAR valor -5");
                erro(l.pedir("LICITAR " + id[1] + " 1000000000"), "VALOR", "LICITAR valor 10 digitos");
                erro(l.pedir("LICITAR abc 5"), "VALOR", "LICITAR id abc");
                erro(l.pedir("LICITAR 0 5"), "VALOR", "LICITAR id 0");
                erro(l.pedir("LICITAR " + id[1]), "SINTAXE", "LICITAR sem valor");
                erro(l.pedir("LICITAR 999999999 5"), "INEXISTENTE", "LICITAR inexistente");
            }
        });
        caso('E', "leilao fechado: LICITAR -> ERRO FECHADO (antes de PROPRIO e de BAIXA); estado FECHADO", () -> {
            try (L d = new L().entrar(DONO); L l = new L().entrar(LIC)) {
                id[2] = criar(d, "100 5 1 fugaz");
                Thread.sleep(2300);   // sem licitar antes: um anti-sniping ligado nao pode adiar este fecho
                erro(l.pedir("LICITAR " + id[2] + " 200"), "FECHADO", "LICITAR em leilao fechado");
                erro(d.pedir("LICITAR " + id[2] + " 200"), "FECHADO", "criador em leilao fechado");
                erro(l.pedir("LICITAR " + id[2] + " 1"), "FECHADO", "valor baixo em leilao fechado");
                java.util.Map<String, String> m = registo(l.pedir("CONSULTAR " + id[2]), "OK ");
                exigir(m.get("estado").equals("FECHADO") && m.get("restante").equals("0") && m.get("melhor").equals("0")
                        && m.get("vencedor").equals("-") && m.get("licitacoes").equals("0"), "estado errado em " + m);
            }
        });
        caso('E', "REGISTAR nao inicia sessao: CRIAR a seguir -> ERRO SEM_SESSAO", () -> {
            try (L l = new L()) {
                exigir(l.pedir("REGISTAR novo" + SUF + " " + PW).equals("OK"), "REGISTAR devia dar OK");
                erro(l.pedir("CRIAR 100 5 60 x"), "SEM_SESSAO", "CRIAR depois de REGISTAR");
            }
        });
        caso('E', "sem sessao, LICITAR com id invalido -> ERRO SEM_SESSAO (antes de VALOR)", () -> {
            try (L l = new L()) {
                erro(l.pedir("LICITAR abc 5"), "SEM_SESSAO", "LICITAR abc 5 sem login");
            }
        });
        caso('E', "restante arredondado para cima: leilao de 60 s consultado 0,3 s depois tem restante=60", () -> {
            try (L l = new L().entrar(DONO)) {
                String ultimo = "";
                for (int tentativa = 0; tentativa < 3; tentativa++) {   // tolera um pedido lento
                    long t0 = System.nanoTime();
                    int n = criar(l, "100 5 60 arredondar");
                    Thread.sleep(300);   // faltam ~59,7 s: arredondado para cima dá 60; para baixo daria 59
                    java.util.Map<String, String> m = registo(l.pedir("CONSULTAR " + n), "OK ");
                    ultimo = m.get("restante");
                    if ((System.nanoTime() - t0) < 800_000_000L) {
                        exigir(ultimo.equals("60"), "restante devia ser 60 (arredondado para cima), foi " + ultimo);
                        return;
                    }
                }
                throw new Falha("o servidor demorou mais de 0,8 s a responder; restante=" + ultimo);
            }
        });
        caso('E', "LISTAR por ordem crescente de id, com os leiloes criados", () -> {
            try (L d = new L().entrar(DONO); L l = new L()) {
                int a = criar(d, "100 5 60 lista A");
                int b = criar(d, "100 5 60 lista B");
                String r = l.pedir("LISTAR");
                exigir(r != null && r.matches("OK [0-9]+"), "LISTAR deu [" + abreviar(r) + "]");
                int n = Integer.parseInt(r.substring(3));
                int anterior = 0;
                boolean viuA = false;
                boolean viuB = false;
                for (int i = 0; i < n; i++) {
                    int lid = Integer.parseInt(registo(l.linha(), "LEILAO ").get("id"));
                    exigir(lid > anterior, "ids fora de ordem crescente: " + lid + " depois de " + anterior);
                    anterior = lid;
                    viuA |= lid == a;
                    viuB |= lid == b;
                }
                exigir(viuA && viuB, "a lista nao tem os dois leiloes criados");
            }
        });
        caso('E', "SAIR com argumentos -> ERRO SINTAXE e a ligacao continua", () -> {
            try (L l = new L()) {
                erro(l.pedir("SAIR ja"), "SINTAXE", "SAIR ja");
                exigir(l.pedir("SAIR").equals("OK"), "SAIR devia dar OK");
            }
        });
        caso('N', "SAIR -> OK e o servidor fecha a ligacao", () -> {
            try (L l = new L()) {
                exigir(l.pedir("SAIR").equals("OK"), "SAIR devia dar OK");
                String r = l.linha();
                exigir(r == null, "a ligacao devia ter sido fechada, mas recebi [" + abreviar(r) + "]");
            }
        });

        System.out.println();
        System.out.println("RESUMO normal [N]: " + passaN + "/" + totalN + "   erro e fronteira [E]: " + passaE + "/" + totalE);
        System.out.println(falhas.isEmpty() ? "PASSA" : "FALHA (" + falhas.size() + " casos)");
        System.exit(falhas.isEmpty() ? 0 : 1);
    }
}
