package servidor;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Gestor de persistencia em ficheiro de texto UTF-8 (Write-Ahead Log).
 *
 * Formato das linhas (campos separados por '\t', terminados por "\tEND\n"):
 *   USER     \t <utilizador> \t <sha256> \t END
 *   CRIAR    \t <id> \t <criador> \t <base> \t <incremento> \t <horaFechoMillis> \t <artigo> \t END
 *   LICITAR  \t <id> \t <licitador> \t <valor> \t <numOrdem> \t <novaHoraFechoMillis> \t <seqAviso> \t END
 *   FECHAR   \t <id> \t <vencedor> \t <seqAviso> \t END
 *   CANCELAR \t <id> \t <seqAviso> \t END
 */
public class Historico implements AutoCloseable {
    private final Path ficheiro;
    private FileOutputStream out;

    public Historico(Path ficheiro) {
        this.ficheiro = ficheiro;
    }

    /**
     * Carrega o historico existente para repor o estado em memoria.
     * Se a ultima linha estiver truncada (por queda durante a gravacao), ignora-a
     * e trunca o ficheiro ate ao ultimo registo valido para nao corromper escritas futuras.
     */
    public synchronized void carregarEAbrir(EstadoServidor estado) throws IOException {
        Path pai = ficheiro.toAbsolutePath().getParent();
        if (pai != null) {
            Files.createDirectories(pai);
        }

        if (Files.exists(ficheiro)) {
            byte[] dados = Files.readAllBytes(ficheiro);
            long ultimoOffsetValido = 0;
            int pos = 0;
            int linhaNum = 0;
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);

            while (pos < dados.length) {
                linhaNum++;
                int inicioLinha = pos;
                boolean viuNewline = false;
                while (pos < dados.length) {
                    if (dados[pos] == '\n') {
                        viuNewline = true;
                        pos++;
                        break;
                    }
                    pos++;
                }

                boolean ehUltimaLinha = (pos == dados.length);
                int fimConteudo = viuNewline ? (pos - 1) : pos;
                if (fimConteudo > inicioLinha && dados[fimConteudo - 1] == '\r') {
                    fimConteudo--;
                }

                if (fimConteudo == inicioLinha) {
                    if (viuNewline) {
                        ultimoOffsetValido = pos;
                    }
                    continue;
                }

                boolean valida = false;
                if (viuNewline) {
                    try {
                        String linha = decoder.decode(ByteBuffer.wrap(dados, inicioLinha, fimConteudo - inicioLinha)).toString();
                        decoder.reset();
                        valida = processarLinhaHistorico(linha, estado);
                    } catch (CharacterCodingException | IllegalArgumentException e) {
                        valida = false;
                    }
                }

                if (!valida) {
                    if (ehUltimaLinha) {
                        System.err.println("[HISTORICO] Ultima linha truncada ou invalida na linha "
                                + linhaNum + " — ignorada no arranque.");
                        try (FileChannel ch = FileChannel.open(ficheiro, StandardOpenOption.WRITE)) {
                            ch.truncate(ultimoOffsetValido);
                        }
                        break;
                    } else {
                        throw new IOException("Registo corrompido no meio do historico na linha " + linhaNum);
                    }
                }

                ultimoOffsetValido = pos;
            }
        }

        this.out = new FileOutputStream(ficheiro.toFile(), true);
    }

    private boolean processarLinhaHistorico(String linha, EstadoServidor estado) {
        String[] p = linha.split("\t", -1);
        if (p.length < 2 || !"END".equals(p[p.length - 1])) {
            return false;
        }
        switch (p[0]) {
            case "USER":
                if (p.length != 4) {
                    return false;
                }
                estado.aplicarRegistoHistorico(p[1], p[2]);
                return true;
            case "CRIAR":
                if (p.length != 8) {
                    return false;
                }
                estado.aplicarCriacaoHistorico(
                        Integer.parseInt(p[1]),
                        p[2],
                        Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]),
                        Long.parseLong(p[5]),
                        p[6]
                );
                return true;
            case "LICITAR":
                if (p.length != 8) {
                    return false;
                }
                estado.aplicarLicitacaoHistorico(
                        Integer.parseInt(p[1]),
                        p[2],
                        Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]),
                        Long.parseLong(p[5]),
                        Integer.parseInt(p[6])
                );
                return true;
            case "FECHAR":
                if (p.length != 5) {
                    return false;
                }
                estado.aplicarFechoHistorico(
                        Integer.parseInt(p[1]),
                        p[2],
                        Integer.parseInt(p[3])
                );
                return true;
            case "CANCELAR":
                if (p.length != 4) {
                    return false;
                }
                estado.aplicarCancelamentoHistorico(
                        Integer.parseInt(p[1]),
                        Integer.parseInt(p[2])
                );
                return true;
            default:
                return false;
        }
    }

    private synchronized void escreverRegisto(String... campos) throws IOException {
        if (out == null) {
            throw new IOException("Historico nao esta aberto para escrita");
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
        for (int i = 0; i < campos.length; i++) {
            if (i > 0) {
                baos.write('\t');
            }
            baos.write(campos[i].getBytes(StandardCharsets.UTF_8));
        }
        baos.write('\t');
        baos.write('E');
        baos.write('N');
        baos.write('D');
        baos.write('\n');

        out.write(baos.toByteArray());
        out.flush();
    }

    public void registarUtilizador(String user, String sha256) throws IOException {
        escreverRegisto("USER", user, sha256);
    }

    public void registarCriacao(int id, String criador, int base, int incremento, long horaFechoMillis, String artigo) throws IOException {
        escreverRegisto("CRIAR",
                Integer.toString(id),
                criador,
                Integer.toString(base),
                Integer.toString(incremento),
                Long.toString(horaFechoMillis),
                artigo);
    }

    public void registarLicitacao(int id, String licitador, int valor, int numOrdem, long novaHoraFechoMillis, int seqAviso) throws IOException {
        escreverRegisto("LICITAR",
                Integer.toString(id),
                licitador,
                Integer.toString(valor),
                Integer.toString(numOrdem),
                Long.toString(novaHoraFechoMillis),
                Integer.toString(seqAviso));
    }

    public void registarFecho(int id, String vencedor, int seqAviso) throws IOException {
        escreverRegisto("FECHAR",
                Integer.toString(id),
                vencedor,
                Integer.toString(seqAviso));
    }

    public void registarCancelamento(int id, int seqAviso) throws IOException {
        escreverRegisto("CANCELAR",
                Integer.toString(id),
                Integer.toString(seqAviso));
    }

    @Override
    public synchronized void close() throws IOException {
        if (out != null) {
            out.flush();
            out.close();
            out = null;
        }
    }
}

