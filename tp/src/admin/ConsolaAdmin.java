package admin;

import comum.AdminRMI;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.List;

/**
 * Consola de administracao em processo separado via Java RMI (requisito C1).
 * Nao guarda estado proprio: todas as operacoes consultam ou alteram o servidor remotamente.
 *
 * Modos de utilizacao:
 *   1. Comando direto: java -cp out admin.ConsolaAdmin [host] [portoRMI] LISTAR | UTILIZADORES | CANCELAR <id>
 *   2. Interativo    : java -cp out admin.ConsolaAdmin [host] [portoRMI]
 */
public class ConsolaAdmin {
    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int portoRmi = 5051;
        int idxCmd = 0;

        if (args.length >= 1 && !ehComando(args[0])) {
            if ("--ajuda".equals(args[0]) || "--help".equals(args[0])) {
                imprimirAjuda();
                return;
            }
            host = args[0];
            idxCmd = 1;
            if (args.length >= 2 && !ehComando(args[1])) {
                portoRmi = Integer.parseInt(args[1]);
                idxCmd = 2;
            }
        }

        Registry registry = LocateRegistry.getRegistry(host, portoRmi);
        AdminRMI admin = (AdminRMI) registry.lookup("LeiloesAdmin");

        if (idxCmd < args.length) {
            String cmd = args[idxCmd].toUpperCase();
            String arg = (idxCmd + 1 < args.length) ? args[idxCmd + 1] : null;
            executarComando(admin, cmd, arg);
            return;
        }

        System.out.println("=== Consola de Administracao (Java RMI: " + host + ":" + portoRmi + ") ===");
        System.out.println("Comandos: LISTAR | UTILIZADORES | CANCELAR <id> | SAIR");

        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("admin> ");
            System.out.flush();
            String linha = br.readLine();
            if (linha == null) {
                break;
            }
            String trimmed = linha.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] partes = trimmed.split("\\s+");
            String cmd = partes[0].toUpperCase();
            if ("SAIR".equals(cmd) || "EXIT".equals(cmd)) {
                break;
            }
            String arg = partes.length > 1 ? partes[1] : null;
            executarComando(admin, cmd, arg);
        }
    }

    private static void executarComando(AdminRMI admin, String cmd, String arg) throws Exception {
        switch (cmd) {
            case "LISTAR": {
                List<String> leiloes = admin.listarLeiloes();
                System.out.println("Leiloes registados (" + leiloes.size() + "):");
                if (leiloes.isEmpty()) {
                    System.out.println("  (nenhum leilao)");
                } else {
                    for (String l : leiloes) {
                        System.out.println("  " + l);
                    }
                }
                break;
            }
            case "UTILIZADORES": {
                List<String> users = admin.listarUtilizadoresOnline();
                System.out.println("Utilizadores com sessao iniciada (" + users.size() + "):");
                if (users.isEmpty()) {
                    System.out.println("  (nenhum utilizador com sessao ativa)");
                } else {
                    for (String u : users) {
                        System.out.println("  - " + u);
                    }
                }
                break;
            }
            case "CANCELAR": {
                if (arg == null) {
                    System.out.println("Uso: CANCELAR <id>");
                    return;
                }
                int id = Integer.parseInt(arg);
                String res = admin.cancelarLeilao(id);
                System.out.println(res);
                break;
            }
            default:
                System.out.println("Comando desconhecido: " + cmd + " (use LISTAR, UTILIZADORES, CANCELAR <id> ou SAIR)");
        }
    }

    private static boolean ehComando(String s) {
        String u = s.toUpperCase();
        return "LISTAR".equals(u) || "UTILIZADORES".equals(u) || "CANCELAR".equals(u);
    }

    private static void imprimirAjuda() {
        System.out.println("Uso: java -cp out admin.ConsolaAdmin [host] [portoRMI] [LISTAR | UTILIZADORES | CANCELAR <id>]");
        System.out.println("Por omissao: host=localhost, portoRMI=5051.");
    }
}

