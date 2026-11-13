package servidor;

/**
 * Representa um leilao em memoria no servidor.
 * Todos os acessos e mutacoes ocorrem sob a protecao do monitor de EstadoServidor.
 */
public class Leilao {
    final int id;
    final String artigo;
    final int base;
    final int incremento;
    final String criador;

    long horaFechoMillis;
    boolean fechado;
    boolean cancelado;
    int melhor;
    String vencedor;
    int licitacoes;
    int sequenciaAviso;

    public Leilao(int id, String artigo, int base, int incremento, String criador, long horaFechoMillis) {
        this.id = id;
        this.artigo = artigo;
        this.base = base;
        this.incremento = incremento;
        this.criador = criador;
        this.horaFechoMillis = horaFechoMillis;
        this.fechado = false;
        this.cancelado = false;
        this.melhor = 0;
        this.vencedor = "-";
        this.licitacoes = 0;
        this.sequenciaAviso = 0;
    }

    /**
     * Calcula o tempo restante em segundos, arredondado para cima conforme o Anexo A.
     */
    public long calcularRestanteSeg(long agoraMillis) {
        if (fechado) {
            return 0L;
        }
        long diffMillis = horaFechoMillis - agoraMillis;
        if (diffMillis <= 0L) {
            return 0L;
        }
        return (diffMillis + 999L) / 1000L;
    }

    /**
     * Formata o registo do leilao exatamente pela ordem de campos do Anexo A.
     */
    public String formatarRegisto(long agoraMillis) {
        String estadoStr = fechado ? "FECHADO" : "ABERTO";
        String vencedorStr = cancelado ? "-" : vencedor;
        long restanteSeg = calcularRestanteSeg(agoraMillis);

        return "id=" + id
                + " estado=" + estadoStr
                + " melhor=" + melhor
                + " vencedor=" + vencedorStr
                + " criador=" + criador
                + " base=" + base
                + " incremento=" + incremento
                + " licitacoes=" + licitacoes
                + " restante=" + restanteSeg
                + " artigo=" + artigo;
    }
}

