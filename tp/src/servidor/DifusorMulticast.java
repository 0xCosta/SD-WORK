package servidor;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;

/**
 * Responsavel por difundir avisos de licitacoes aceites, fechos e cancelamentos
 * para o grupo multicast UDP configurado.
 *
 * Formato dos avisos (UTF-8, uma linha de pares chave=valor):
 *   AVISO id=<id> seq=<seq> tipo=LICITACAO valor=<val> licitador=<user> restante=<seg> adiado=<true|false>
 *   AVISO id=<id> seq=<seq> tipo=FECHO melhor=<val> vencedor=<user|-> licitacoes=<n>
 *   AVISO id=<id> seq=<seq> tipo=CANCELADO melhor=<val> vencedor=- licitacoes=<n>
 */
public class DifusorMulticast implements AutoCloseable {
    private final InetAddress grupo;
    private final int porto;
    private final MulticastSocket socket;

    public DifusorMulticast(String enderecoGrupo, int porto) throws IOException {
        this.grupo = InetAddress.getByName(enderecoGrupo);
        this.porto = porto;
        this.socket = new MulticastSocket();
        this.socket.setTimeToLive(1);
    }

    public void enviarLicitacao(int id, int seq, int valor, String licitador, long restanteSeg, boolean adiado) {
        String msg = "AVISO id=" + id
                + " seq=" + seq
                + " tipo=LICITACAO"
                + " valor=" + valor
                + " licitador=" + licitador
                + " restante=" + restanteSeg
                + " adiado=" + adiado;
        enviar(msg);
    }

    public void enviarFecho(int id, int seq, int melhor, String vencedor, int licitacoes) {
        String msg = "AVISO id=" + id
                + " seq=" + seq
                + " tipo=FECHO"
                + " melhor=" + melhor
                + " vencedor=" + vencedor
                + " licitacoes=" + licitacoes;
        enviar(msg);
    }

    public void enviarCancelamento(int id, int seq, int melhor, int licitacoes) {
        String msg = "AVISO id=" + id
                + " seq=" + seq
                + " tipo=CANCELADO"
                + " melhor=" + melhor
                + " vencedor=-"
                + " licitacoes=" + licitacoes;
        enviar(msg);
    }

    private synchronized void enviar(String mensagem) {
        byte[] dados = mensagem.getBytes(StandardCharsets.UTF_8);
        DatagramPacket pacote = new DatagramPacket(dados, dados.length, grupo, porto);
        try {
            socket.send(pacote);
        } catch (IOException e) {
            System.err.println("[MULTICAST] Erro ao enviar aviso: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        socket.close();
    }
}

