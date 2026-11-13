package comum;

import java.util.HashMap;
import java.util.Map;

/**
 * Utilitarios de parsing partilhados entre Cliente e ferramentas de demonstracao.
 */
public final class Mensagens {
    private Mensagens() {
    }

    /**
     * Faz parsing de pares chave=valor separados por espaco, preservando artigo=... no fim.
     */
    public static Map<String, String> parseChaveValor(String texto) {
        Map<String, String> m = new HashMap<>();
        int i = 0;
        int n = texto.length();
        while (i < n) {
            while (i < n && texto.charAt(i) == ' ') {
                i++;
            }
            if (i >= n) {
                break;
            }
            int eq = texto.indexOf('=', i);
            if (eq < 0) {
                break;
            }
            String chave = texto.substring(i, eq);
            if ("artigo".equals(chave)) {
                m.put(chave, texto.substring(eq + 1));
                break;
            }
            int sp = texto.indexOf(' ', eq + 1);
            if (sp < 0) {
                m.put(chave, texto.substring(eq + 1));
                break;
            }
            m.put(chave, texto.substring(eq + 1, sp));
            i = sp + 1;
        }
        return m;
    }
}

