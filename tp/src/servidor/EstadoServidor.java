package servidor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Estado partilhado central do servidor e ponto unico de sincronizacao (regiao critica).
 *
 * Todos os metodos que leem ou alteram utilizadores, leiloes ou sessoes ativas sao
 * synchronized no monitor desta instancia. Tanto as threads TCP (AtendedorCliente),
 * como as threads Java RMI (ConsolaAdmin) e o temporizador de fecho (I3) competem
 * pelo mesmo lock, garantindo atomicidade estrita entre licitacoes simultaneas (B5),
 * cancelamentos RMI (C1) e fechos pelo relogio do servidor (B6/I3/C2).
 */
public class EstadoServidor {
    private final Map<String, String> utilizadores = new HashMap<>();
    private final Map<Integer, Leilao> leiloes = new TreeMap<>();
    private final Map<String, Integer> sessoesAtivas = new TreeMap<>();
    private int proximoId = 1;

    private final Historico historico;
    private final DifusorMulticast difusor;
    private final int antiSnipingSeg;

    public EstadoServidor(Historico historico, DifusorMulticast difusor, int antiSnipingSeg) {
        this.historico = historico;
        this.difusor = difusor;
        this.antiSnipingSeg = Math.max(0, antiSnipingSeg);
    }

    /**
     * Calcula o resumo SHA-256 em hexadecimal minusculo (requisito B3).
     * A palavra-passe nunca e guardada nem escrita em claro.
     */
    public static String sha256(String texto) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(texto.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algoritmo SHA-256 indisponivel", e);
        }
    }

    // --- Metodos de reconstrucao do historico no arranque ---

    synchronized void aplicarRegistoHistorico(String user, String hashSha256) {
        if (user == null || user.isEmpty() || hashSha256 == null || hashSha256.length() != 64) {
            throw new IllegalArgumentException("Registo USER invalido no historico");
        }
        utilizadores.put(user, hashSha256);
    }

    synchronized void aplicarCriacaoHistorico(int id, String criador, int base, int incremento, long horaFechoMillis, String artigo) {
        if (id <= 0 || base < 0 || incremento <= 0 || artigo.isEmpty()) {
            throw new IllegalArgumentException("Registo CRIAR invalido no historico");
        }
        Leilao l = new Leilao(id, artigo, base, incremento, criador, horaFechoMillis);
        leiloes.put(id, l);
        if (id >= proximoId) {
            proximoId = id + 1;
        }
    }

    synchronized void aplicarLicitacaoHistorico(int id, String licitador, int valor, int numOrdem, long novaHoraFechoMillis, int seqAviso) {
        Leilao l = leiloes.get(id);
        if (l == null) {
            throw new IllegalArgumentException("Registo LICITAR para leilao inexistente: " + id);
        }
        l.licitacoes = numOrdem;
        l.melhor = valor;
        l.vencedor = licitador;
        l.horaFechoMillis = novaHoraFechoMillis;
        l.sequenciaAviso = seqAviso;
    }

    synchronized void aplicarFechoHistorico(int id, String vencedor, int seqAviso) {
        Leilao l = leiloes.get(id);
        if (l == null) {
            throw new IllegalArgumentException("Registo FECHAR para leilao inexistente: " + id);
        }
        l.fechado = true;
        l.vencedor = vencedor;
        l.sequenciaAviso = seqAviso;
    }

    synchronized void aplicarCancelamentoHistorico(int id, int seqAviso) {
        Leilao l = leiloes.get(id);
        if (l == null) {
            throw new IllegalArgumentException("Registo CANCELAR para leilao inexistente: " + id);
        }
        l.fechado = true;
        l.cancelado = true;
        l.vencedor = "-";
        l.sequenciaAviso = seqAviso;
    }

    // --- Operacoes do protocolo TCP (chamadas por AtendedorCliente) ---

    public synchronized String registar(String user, String password) {
        if (utilizadores.containsKey(user)) {
            return "ERRO EXISTE utilizador ja registado";
        }
        String hash = sha256(password);
        try {
            historico.registarUtilizador(user, hash);
        } catch (IOException e) {
            return "ERRO INTERNO falha ao gravar registo";
        }
        utilizadores.put(user, hash);
        return "OK";
    }

    public synchronized String login(String user, String password) {
        String hashEsperado = utilizadores.get(user);
        if (hashEsperado == null || !hashEsperado.equals(sha256(password))) {
            return "ERRO CREDENCIAIS utilizador ou palavra-passe invalidos";
        }
        sessoesAtivas.merge(user, 1, Integer::sum);
        return "OK";
    }

    public synchronized void terminarSessao(String user) {
        if (user == null) {
            return;
        }
        sessoesAtivas.computeIfPresent(user, (k, v) -> v > 1 ? v - 1 : null);
    }

    public synchronized String criarLeilao(String criador, int base, int incremento, int duracaoSeg, String artigo) {
        int id = proximoId;
        long agora = System.currentTimeMillis();
        long horaFecho = agora + duracaoSeg * 1000L;
        try {
            historico.registarCriacao(id, criador, base, incremento, horaFecho, artigo);
        } catch (IOException e) {
            return "ERRO INTERNO falha ao gravar leilao";
        }
        Leilao l = new Leilao(id, artigo, base, incremento, criador, horaFecho);
        leiloes.put(id, l);
        proximoId++;
        return "OK id=" + id;
    }

    public synchronized List<String> listar() {
        long agora = System.currentTimeMillis();
        fecharTodosExpirados(agora);

        List<String> resposta = new ArrayList<>(leiloes.size() + 1);
        resposta.add("OK " + leiloes.size());
        for (Leilao l : leiloes.values()) {
            resposta.add("LEILAO " + l.formatarRegisto(agora));
        }
        return resposta;
    }

    public synchronized String consultar(int id) {
        Leilao l = leiloes.get(id);
        if (l == null) {
            return "ERRO INEXISTENTE leilao nao existe";
        }
        long agora = System.currentTimeMillis();
        fecharSeExpirado(l, agora);
        return "OK " + l.formatarRegisto(agora);
    }

    /**
     * Extensao ao protocolo (comando ESTADO <id>) para recuperacao de perdas UDP via TCP (I2).
     */
    public synchronized String estadoEstendido(int id) {
        Leilao l = leiloes.get(id);
        if (l == null) {
            return "ERRO INEXISTENTE leilao nao existe";
        }
        long agora = System.currentTimeMillis();
        fecharSeExpirado(l, agora);
        return "OK seq=" + l.sequenciaAviso + " cancelado=" + l.cancelado + " " + l.formatarRegisto(agora);
    }

    public synchronized String licitar(String licitador, int id, int valor) {
        Leilao l = leiloes.get(id);
        if (l == null) {
            return "ERRO INEXISTENTE leilao nao existe";
        }

        long agora = System.currentTimeMillis();
        fecharSeExpirado(l, agora);

        if (l.fechado || agora >= l.horaFechoMillis) {
            return "ERRO FECHADO leilao ja fechou";
        }
        if (l.criador.equals(licitador)) {
            return "ERRO PROPRIO o criador nao pode licitar no seu leilao";
        }

        long minimo = (l.licitacoes == 0)
                ? (long) l.base
                : (long) l.melhor + (long) l.incremento;
        if (valor < minimo) {
            return "ERRO BAIXA valor inferior ao minimo";
        }

        int novoNumOrdem = l.licitacoes + 1;
        int novoSeq = l.sequenciaAviso + 1;
        long novaHoraFecho = l.horaFechoMillis;
        boolean adiado = false;

        if (antiSnipingSeg > 0) {
            long restanteAntesMillis = l.horaFechoMillis - agora;
            long janelaMillis = antiSnipingSeg * 1000L;
            if (restanteAntesMillis <= janelaMillis) {
                novaHoraFecho = agora + janelaMillis;
                adiado = true;
            }
        }

        try {
            historico.registarLicitacao(id, licitador, valor, novoNumOrdem, novaHoraFecho, novoSeq);
        } catch (IOException e) {
            return "ERRO INTERNO falha ao gravar licitacao";
        }

        l.licitacoes = novoNumOrdem;
        l.melhor = valor;
        l.vencedor = licitador;
        l.horaFechoMillis = novaHoraFecho;
        l.sequenciaAviso = novoSeq;

        if (difusor != null) {
            long restanteSeg = l.calcularRestanteSeg(agora);
            difusor.enviarLicitacao(id, novoSeq, valor, licitador, restanteSeg, adiado);
        }

        return "OK n=" + novoNumOrdem;
    }

    // --- Fecho automatico pelo relogio do servidor (I3 e I4) ---

    public synchronized int fecharLeiloesVencidos() {
        return fecharTodosExpirados(System.currentTimeMillis());
    }

    private int fecharTodosExpirados(long agora) {
        int fechados = 0;
        for (Leilao l : leiloes.values()) {
            if (fecharSeExpirado(l, agora)) {
                fechados++;
            }
        }
        return fechados;
    }

    private boolean fecharSeExpirado(Leilao l, long agora) {
        if (l.fechado || agora < l.horaFechoMillis) {
            return false;
        }
        int novoSeq = l.sequenciaAviso + 1;
        try {
            historico.registarFecho(l.id, l.vencedor, novoSeq);
        } catch (IOException e) {
            System.err.println("[ERRO] Falha ao gravar fecho do leilao #" + l.id + ": " + e.getMessage());
            return false;
        }
        l.fechado = true;
        l.sequenciaAviso = novoSeq;

        if (difusor != null) {
            difusor.enviarFecho(l.id, novoSeq, l.melhor, l.vencedor, l.licitacoes);
        }
        return true;
    }

    // --- Operacoes de administracao via Java RMI (C1) ---

    public synchronized List<String> adminListarLeiloes() {
        long agora = System.currentTimeMillis();
        fecharTodosExpirados(agora);

        List<String> lista = new ArrayList<>(leiloes.size());
        for (Leilao l : leiloes.values()) {
            lista.add(l.formatarRegisto(agora) + " seq=" + l.sequenciaAviso + " cancelado=" + l.cancelado);
        }
        return lista;
    }

    public synchronized List<String> adminListarUtilizadoresOnline() {
        return new ArrayList<>(sessoesAtivas.keySet());
    }

    public synchronized String adminCancelarLeilao(int id) {
        Leilao l = leiloes.get(id);
        if (l == null) {
            return "ERRO INEXISTENTE leilao " + id + " nao existe";
        }
        long agora = System.currentTimeMillis();
        fecharSeExpirado(l, agora);
        if (l.fechado) {
            return "ERRO FECHADO leilao " + id + " ja esta fechado";
        }

        int novoSeq = l.sequenciaAviso + 1;
        try {
            historico.registarCancelamento(id, novoSeq);
        } catch (IOException e) {
            return "ERRO INTERNO falha ao gravar cancelamento";
        }

        l.fechado = true;
        l.cancelado = true;
        l.vencedor = "-";
        l.sequenciaAviso = novoSeq;

        if (difusor != null) {
            difusor.enviarCancelamento(id, novoSeq, l.melhor, l.licitacoes);
        }
        return "OK leilao " + id + " cancelado (seq=" + novoSeq + ")";
    }
}

