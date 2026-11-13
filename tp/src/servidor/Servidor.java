package servidor;

import comum.AdminRMI;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Processo principal do servidor de leiloes em tempo real.
 */
public class Servidor {
    // Referencia estatica forte para evitar recolha pelo Garbage Collector do objeto remoto RMI
    private static AdminRMIImpl adminServicoRef;

    private static final class AdminRMIImpl extends UnicastRemoteObject implements AdminRMI {
        private static final long serialVersionUID = 1L;
        private final EstadoServidor estado;

        AdminRMIImpl(EstadoServidor estado) throws RemoteException {
            super();
            this.estado = estado;
        }

        @Override
        public List<String> listarLeiloes() {
            return estado.adminListarLeiloes();
        }

        @Override
        public List<String> listarUtilizadoresOnline() {
            return estado.adminListarUtilizadoresOnline();
        }

        @Override
        public String cancelarLeilao(int id) {
            return estado.adminCancelarLeilao(id);
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.net.preferIPv4Stack", "true");

        int portoTcp = 5050;
        int portoRmi = 5051;
        String grupoMulticast = "239.255.0.1";
        int portoUdp = 6060;
        int antiSnipingSeg = 30;
        String ficheiroHistorico = "historico.log";
        String rmiHost = "localhost";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--porto":
                    portoTcp = Integer.parseInt(args[++i]);
                    break;
                case "--rmi":
                    portoRmi = Integer.parseInt(args[++i]);
                    break;
                case "--grupo":
                    grupoMulticast = args[++i];
                    break;
                case "--udp":
                    portoUdp = Integer.parseInt(args[++i]);
                    break;
                case "--antisniping":
                    antiSnipingSeg = Integer.parseInt(args[++i]);
                    break;
                case "--historico":
                    ficheiroHistorico = args[++i];
                    break;
                case "--rmi-host":
                    rmiHost = args[++i];
                    break;
                case "--ajuda":
                case "--help":
                    imprimirAjuda();
                    return;
                default:
                    System.err.println("Argumento desconhecido: " + args[i]);
                    imprimirAjuda();
                    System.exit(1);
            }
        }

        validarPorto(portoTcp, "TCP");
        validarPorto(portoRmi, "RMI");
        validarPorto(portoUdp, "UDP");
        if (portoTcp == portoRmi) {
            throw new IllegalArgumentException("O porto RMI deve ser diferente do porto TCP");
        }

        System.setProperty("java.rmi.server.hostname", rmiHost);

        Historico historico = new Historico(Path.of(ficheiroHistorico));
        DifusorMulticast difusor = new DifusorMulticast(grupoMulticast, portoUdp);
        EstadoServidor estado = new EstadoServidor(historico, difusor, antiSnipingSeg);

        historico.carregarEAbrir(estado);
        int fechadosNoArranque = estado.fecharLeiloesVencidos();
        if (fechadosNoArranque > 0) {
            System.out.println("[ARRANQUE] Fechados " + fechadosNoArranque + " leiloes vencidos durante a paragem.");
        }

        Registry registry = LocateRegistry.createRegistry(portoRmi);
        adminServicoRef = new AdminRMIImpl(estado);
        registry.rebind("LeiloesAdmin", adminServicoRef);

        ScheduledExecutorService temporizador = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "FechoAutomatico");
            t.setDaemon(true);
            return t;
        });
        temporizador.scheduleAtFixedRate(estado::fecharLeiloesVencidos, 100, 150, TimeUnit.MILLISECONDS);

        ExecutorService poolClientes = Executors.newCachedThreadPool();
        Set<Socket> socketsAtivos = ConcurrentHashMap.newKeySet();
        ServerSocket serverSocket = new ServerSocket(portoTcp);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            encerrarRecursos(serverSocket, socketsAtivos, poolClientes, temporizador, difusor, historico);
        }, "ShutdownServidor"));

        System.out.println("=== Servidor de Leiloes em Tempo Real (Nivel Completo) ===");
        System.out.println("TCP       : porto " + portoTcp);
        System.out.println("RMI       : " + rmiHost + ":" + portoRmi + " (servico LeiloesAdmin)");
        System.out.println("Multicast : " + grupoMulticast + ":" + portoUdp);
        System.out.println("Anti-snipe: " + antiSnipingSeg + " s");
        System.out.println("Historico : " + Path.of(ficheiroHistorico).toAbsolutePath());

        try {
            while (!serverSocket.isClosed()) {
                Socket cliente = serverSocket.accept();
                socketsAtivos.add(cliente);
                poolClientes.execute(new AtendedorCliente(cliente, estado, socketsAtivos));
            }
        } catch (IOException e) {
            if (!serverSocket.isClosed()) {
                System.err.println("[SERVIDOR] Erro no ServerSocket: " + e.getMessage());
            }
        } finally {
            encerrarRecursos(serverSocket, socketsAtivos, poolClientes, temporizador, difusor, historico);
        }
    }

    private static void encerrarRecursos(
            ServerSocket serverSocket,
            Set<Socket> socketsAtivos,
            ExecutorService poolClientes,
            ScheduledExecutorService temporizador,
            DifusorMulticast difusor,
            Historico historico) {
        try {
            if (!serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        for (Socket s : socketsAtivos) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        temporizador.shutdownNow();
        poolClientes.shutdownNow();
        if (adminServicoRef != null) {
            try {
                UnicastRemoteObject.unexportObject(adminServicoRef, true);
            } catch (Exception ignored) {
            }
        }
        difusor.close();
        try {
            historico.close();
        } catch (IOException ignored) {
        }
    }

    private static void validarPorto(int porto, String nome) {
        if (porto < 1024 || porto > 65535 || porto == 5000) {
            throw new IllegalArgumentException(
                    "Porto " + nome + " invalido (" + porto + "): usar >= 1024 e nunca 5000.");
        }
    }

    private static void imprimirAjuda() {
        System.out.println("Uso: java -Djava.net.preferIPv4Stack=true -cp out servidor.Servidor [opcoes]");
        System.out.println("  --porto <n>        Porto TCP (omissao: 5050)");
        System.out.println("  --rmi <n>          Porto Java RMI (omissao: 5051)");
        System.out.println("  --grupo <ip>       Grupo UDP multicast (omissao: 239.255.0.1)");
        System.out.println("  --udp <n>          Porto UDP multicast (omissao: 6060)");
        System.out.println("  --antisniping <s>  Janela de anti-sniping em segundos (omissao: 30)");
        System.out.println("  --historico <fich> Caminho do ficheiro de historico (omissao: historico.log)");
        System.out.println("  --rmi-host <host>  Hostname RMI (omissao: localhost)");
    }
}

