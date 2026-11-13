package comum;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * Interface remota Java RMI para a Consola de Administracao (requisito C1).
 * A consola corre num processo separado e nao guarda estado proprio.
 */
public interface AdminRMI extends Remote {

    /**
     * Lista todos os leiloes existentes no servidor (por ordem crescente de id).
     */
    List<String> listarLeiloes() throws RemoteException;

    /**
     * Lista os utilizadores com pelo menos uma sessao TCP iniciada neste momento.
     */
    List<String> listarUtilizadoresOnline() throws RemoteException;

    /**
     * Cancela um leilao aberto (sem vencedor, regista no historico e difunde aviso multicast).
     * Devolve "OK ..." em caso de sucesso ou "ERRO <CODIGO> <motivo>" caso contrario.
     */
    String cancelarLeilao(int id) throws RemoteException;
}

