# Passo a Passo Prático para o 20/20 — Leilões em Tempo Real (SD 2026/2027)

Este documento é o teu **guião de implementação passo a passo**, pensado para construíres o projeto do zero, pela ordem certa, testando cada etapa antes de avançar para a seguinte.

---

## Visão Geral dos Ficheiros que vais criar

Para cumprir a regra de compilar tudo facilmente na linha de comandos (`javac --release 17 -encoding UTF-8 *.java`), coloca todos os ficheiros `.java` na mesma pasta (sem `package`):

1. `Leilao.java` — Modelo de dados de um leilão e formatação da linha de registo.
2. `Historico.java` — Leitura e escrita persistente em ficheiro (`historico.log`), tolerante a última linha truncada.
3. `DifusorMulticast.java` — Envio de datagramas UDP multicast para o grupo dos clientes.
4. `EstadoServidor.java` — **Coração do sistema (Região Crítica):** guarda utilizadores, leilões e sessões; sincroniza TCP, RMI e o temporizador de fecho.
5. `AtendedorCliente.java` — Thread que processa os comandos TCP de um cliente cumprindo o Anexo A à letra.
6. `AdminRMI.java` — Interface `Remote` para a administração por Java RMI.
7. `Servidor.java` — Processo principal do servidor (arranca histórico, temporizador de fecho, RMI e `ServerSocket` TCP com `newCachedThreadPool()`).
8. `Cliente.java` — Consola do utilizador (envia comandos TCP, escuta avisos UDP multicast em tempo real, simula perdas e recupera estado por TCP).
9. `ConsolaAdmin.java` — Processo separado da consola de administração RMI.

---

## PASSO 1: Criar o Modelo do Leilão e Utilitário SHA-256 (`Leilao.java`)

### O que fazer:
1. Criar a classe `Leilao` com os campos:
   - `int id`
   - `String artigo`
   - `int base`
   - `int incremento`
   - `String criador`
   - `long horaFechoMillis` (timestamp em milissegundos pelo relógio do servidor)
   - `boolean fechado`
   - `boolean cancelado`
   - `int melhor` (`0` inicialmente)
   - `String vencedor` (`"-"` inicialmente)
   - `int licitacoes` (`0` inicialmente)
   - `int sequenciaAviso` (`0` inicialmente — usado no Nível Intermédio para numerar os avisos UDP `1, 2, 3...`)

2. Criar o método `formatarRegisto(long agoraMillis)` que devolve exatamente o formato exigido pelo Anexo A:
   ```java
   public String formatarRegisto(long agoraMillis) {
       String estadoStr = fechado ? "FECHADO" : "ABERTO";
       String vencStr = cancelado ? "-" : vencedor;
       long restanteSeg = 0;
       if (!fechado) {
           long diff = horaFechoMillis - agoraMillis;
           restanteSeg = diff <= 0 ? 0 : (long) Math.ceil(diff / 1000.0);
       }
       return "id=" + id
            + " estado=" + estadoStr
            + " melhor=" + melhor
            + " vencedor=" + vencStr
            + " criador=" + criador
            + " base=" + base
            + " incremento=" + incremento
            + " licitacoes=" + licitacoes
            + " restante=" + restanteSeg
            + " artigo=" + artigo;
   }
   ```
   > **Porque é que isto é crítico?** O `Math.ceil(diff / 1000.0)` garante que passas no teste da linha 384 do `Conformidade.java` (arredondamento de `restante` para cima), e o `cancelado ? "-" : vencedor` garante que um leilão cancelado (`C1`) mostra `vencedor=-` e `restante=0` mantendo `melhor` e `licitacoes`.

---

## PASSO 2: Implementar o Ficheiro de Histórico (`Historico.java`) — Requisito B7

### O que fazer:
1. Desenhar um formato de texto simples (uma operação por linha, campos separados por `\t` ou `|`, deixando o artigo sempre no fim):
   - `USER\t<utilizador>\t<hash_sha256>`
   - `CRIAR\t<id>\t<criador>\t<base>\t<incremento>\t<horaFechoMillis>\t<artigo>`
   - `LICITAR\t<id>\t<licitador>\t<valor>\t<numOrdem>\t<novaHoraFechoMillis>\t<seqAviso>`
   - `FECHAR\t<id>\t<vencedor>\t<seqAviso>`
   - `CANCELAR\t<id>\t<seqAviso>`

2. **Regra para a escrita:**
   - Sempre que uma operação altera o estado, escreve a linha completa terminada por `\n` e chama **imediatamente `writer.flush()`** antes de o servidor responder `OK` ao cliente.

3. **Regra para a leitura no arranque (Tolerância a última linha truncada):**
   - Ler o ficheiro linha a linha com `Files.readAllLines()` ou `BufferedReader`.
   - Para garantir que uma escrita interrompida a meio (servidor morreu a gravar) não estraga o arranque, podes terminar cada linha gravada com um marcador de fim (ex: `\tFIM`) ou validar o número de campos num bloco `try-catch`:
     - Se a **última linha** do ficheiro não tiver todos os campos válidos (ou não terminar de forma válida), o servidor **ignora essa última linha**, imprime um aviso no log de arranque e continua normalmente com todo o estado anterior reposto!

---

## PASSO 3: Construir a Região Crítica (`EstadoServidor.java`) — Requisitos B3, B4, B5, B6

### O que fazer:
1. Centralizar todo o estado partilhado numa única classe `EstadoServidor`:
   - `Map<String, String> utilizadores` (`utilizador -> hashSHA256`)
   - `Map<Integer, Leilao> leiloes` (`id -> Leilao`, usando `TreeMap` para manter já ordenado por `id` crescente para o comando `LISTAR`!)
   - `Map<String, Integer> sessoesAtivas` (para saber que utilizadores têm sessão iniciada na consola RMI `C1`)
   - `int proximoId = 1`

2. **Proteger todos os métodos públicos com `synchronized`:**
   - `public synchronized String registar(String user, String password)`
   - `public synchronized String login(String user, String password)`
   - `public synchronized void sairSessao(String user)`
   - `public synchronized String criarLeilao(String criador, int base, int incremento, int duracaoSeg, String artigo)`
   - `public synchronized String licitar(String licitador, int id, int valor)`
   - `public synchronized String consultar(int id)`
   - `public synchronized List<String> listar()`
   - `public synchronized boolean cancelarLeilao(int id)` (chamado pelo RMI)
   - `public synchronized void fecharLeiloesVencidos()` (chamado pelo temporizador periódico)

3. **Método de Hash SHA-256 (`B3`):**
   ```java
   public static String sha256(String texto) {
       try {
           MessageDigest md = MessageDigest.getInstance("SHA-256");
           byte[] hash = md.digest(texto.getBytes(StandardCharsets.UTF_8));
           StringBuilder sb = new StringBuilder();
           for (byte b : hash) {
               sb.append(String.format("%02x", b));
           }
           return sb.toString();
       } catch (Exception e) {
           throw new RuntimeException(e);
       }
   }
   ```

4. **Lógica exata dentro de `licitar(String licitador, int id, int valor)`:**
   - Obter `long agora = System.currentTimeMillis();`
   - Procurar `Leilao l = leiloes.get(id);` -> se `null`, devolver `"ERRO INEXISTENTE leilao nao existe"`.
   - Verificar se já fechou pela hora: se `!l.fechado && agora >= l.horaFechoMillis`, fechar logo o leilão (gravar `FECHAR` no histórico e enviar multicast).
   - Se `l.fechado` -> devolver `"ERRO FECHADO leilao ja fechado"`.
   - Se `l.criador.equals(licitador)` -> devolver `"ERRO PROPRIO o criador nao pode licitar no seu leilao"`.
   - Calcular mínimo: `long minimo = (l.licitacoes == 0) ? l.base : (long) l.melhor + l.incremento;`
   - Se `valor < minimo` -> devolver `"ERRO BAIXA valor inferior ao minimo"`.
   - **Se passou tudo (Licitação Aceite!):**
     1. `l.licitacoes++;`
     2. `l.melhor = valor;`
     3. `l.vencedor = licitador;`
     4. **Anti-sniping (`C2`):** se `janelaAntiSnipingSeg > 0` e `(l.horaFechoMillis - agora) <= janelaAntiSnipingSeg * 1000L`, então `l.horaFechoMillis = agora + janelaAntiSnipingSeg * 1000L`.
     5. `l.sequenciaAviso++;`
     6. **Gravar no `Historico` e fazer `flush()` ANTES de retornar!**
     7. Disparar aviso multicast UDP (`DifusorMulticast`).
     8. Devolver `"OK n=" + l.licitacoes`.

---

## PASSO 4: Parser do Protocolo Base e `AtendedorCliente.java` (Meta: 30/30 no `Conformidade.java`)

### O que fazer:
1. **Leitura segura de linhas até 1024 caracteres:**
   - Criar um método `lerLinha(Reader in)` que lê caractere a caractere até encontrar `\n`:
     - Se encontrar `\r` imediatamente antes de `\n`, remove esse `\r`.
     - Se a linha tiver mais de **1024 caracteres**, continua a ler até consumir o `\n` (para não deixar lixo no socket) e devolve uma flag de linha demasiado longa, respondendo `ERRO SINTAXE linha excede 1024 caracteres` **sem fechar o socket**.

2. **Validação estrita pela Ordem do Anexo A.3:**
   - **Regras de `SINTAXE`:**
     - Linha vazia (`""`), linha `> 1024` chars, ou linha que começa por espaço (`startsWith(" ")`) -> `ERRO SINTAXE`.
     - Extrair a 1.ª palavra (comando).
     - Para `LISTAR` e `SAIR`: a linha tem de ser exatamente `"LISTAR"` ou `"SAIR"`. Qualquer espaço extra dá `ERRO SINTAXE`.
     - Para `CONSULTAR`: não pode acabar em espaço; `split(" ", -1)` tem de ter tamanho `2` e `partes[1]` não vazio.
     - Para `REGISTAR`, `LOGIN`, `LICITAR`: não pode acabar em espaço; `split(" ", -1)` tem de ter tamanho `3` e `partes[1]`, `partes[2]` não vazios.
     - Para `CRIAR`: usar `linha.split(" ", 5)`. Se `partes.length < 5` ou `partes[1].isEmpty()` ou `partes[2].isEmpty()` ou `partes[3].isEmpty()` -> `ERRO SINTAXE`. (O `partes[4]` é o artigo e é validado em `VALOR`!).

   - **Regras de `SEM_SESSAO` (em `CRIAR` e `LICITAR`):**
     - Verificar `if (utilizadorAutenticado == null) return "ERRO SEM_SESSAO faca LOGIN primeiro";` **imediatamente a seguir a `SINTAXE` e antes de validar `VALOR`!**

   - **Regras de `VALOR`:**
     - Função auxiliar para inteiros:
       ```java
       static Integer lerInteiro(String s, int min, int max) {
           if (!s.matches("[0-9]{1,9}")) return null;
           long v = Long.parseLong(s);
           if (v < min || v > max) return null;
           return (int) v;
       }
       ```
     - `utilizador`: `s.matches("[A-Za-z0-9_]{3,20}")`
     - `palavra-passe`: `s.length() >= 1 && s.length() <= 64` e nenhum caractere com `c <= ' ' || c == 127 || Character.isISOControl(c)`.
     - `artigo` (no `CRIAR`): `s.length() >= 1 && s.length() <= 60 && !s.startsWith(" ") && !s.endsWith(" ")` e sem caracteres de controlo (`!Character.isISOControl(c)`).

3. **Testar logo com o `Conformidade.java`:**
   ```bash
   javac --release 17 -encoding UTF-8 *.java
   java Servidor
   # Noutro terminal:
   java Conformidade localhost 5050
   ```
   **Não avances para o Passo 5 enquanto não aparecer `RESUMO normal [N]: 9/9   erro e fronteira [E]: 21/21   PASSA`!**

---

## PASSO 5: Fecho Automático (`I3`) e Fecho no Arranque (`I4`)

### O que fazer:
1. **No arranque do `Servidor` (`I4`):**
   - Logo após `historico.carregar(estado)`, chamar `estado.fecharLeiloesVencidos()`.
   - Isto garante que se um leilão devia ter fechado enquanto o servidor esteve desligado, é fechado imediatamente no arranque, gravado no histórico e anunciado por multicast.
2. **Em execução contínua (`I3`):**
   - No `Servidor`, criar um `ScheduledExecutorService`:
     ```java
     ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
     scheduler.scheduleAtFixedRate(() -> estado.fecharLeiloesVencidos(), 100, 200, TimeUnit.MILLISECONDS);
     ```
   - Como corre a cada `200 ms`, qualquer leilão cuja `horaFechoMillis <= System.currentTimeMillis()` é fechado com atraso máximo de `0,2 s` (muito abaixo do limite de `1 s` exigido em `I3`).

---

## PASSO 6: Difusão Multicast UDP (`I1`) e Cliente com Recuperação (`I2`)

### O que fazer:
1. **Desenhar o formato do aviso Multicast (`DifusorMulticast.java`):**
   - Usar um formato de uma linha em UTF-8 fácil de ler e de fazer parse no cliente:
     - Licitação aceite:
       `AVISO id=<id> seq=<seq> tipo=LICITACAO valor=<melhor> licitador=<vencedor> restante=<restante>`
     - Fecho normal:
       `AVISO id=<id> seq=<seq> tipo=FECHO melhor=<melhor> vencedor=<vencedor>`
     - Cancelamento pela consola RMI:
       `AVISO id=<id> seq=<seq> tipo=CANCELADO`
   - Enviar por `DatagramSocket` para o grupo (omissão `239.255.0.1`) e porto (omissão `6060`).

2. **Adicionar comando de extensão TCP `ESTADO <id>` no Servidor (opcional mas muito útil para `I2`):**
   - Além do `CONSULTAR <id>` (que nunca é alterado!), podes adicionar o comando `ESTADO <id>` que devolve:
     `OK seq=<sequenciaAviso> <registo_normal_do_leilao>`
   - Assim o cliente sabe exatamente qual é o `sequenciaAviso` atual (quer tenha havido apenas licitações, quer já tenha havido fecho/cancelamento).

3. **Implementar o acompanhamento e recuperação no `Cliente.java` (`I2`):**
   - O cliente tem uma thread que abre um `MulticastSocket(portoUdp)` e faz `joinGroup(...)`.
   - Mantém um mapa `Map<Integer, Integer> ultimoSeqVisto` para os leilões que o utilizador está a acompanhar.
   - **Opção de simulação de perdas:** se o cliente foi iniciado com `--descartar N` (ex: `--descartar 3`), um contador incrementa a cada datagrama recebido para o leilão acompanhado e **ignora silenciosamente 1 em cada $N$ avisos** (imprimindo `[SIMULACAO] Aviso descartado para testar recuperacao`).
   - Quando processa um aviso do leilão `id` com sequência `seq`:
     - Se `seq == ultimoSeqVisto + 1`: mostra o aviso em tempo real e faz `ultimoSeqVisto = seq`.
     - Se `seq > ultimoSeqVisto + 1`:
       1. Imprime `[PERDA DETETADA] Esperava seq=" + (ultimoSeqVisto + 1) + ", recebeu seq=" + seq + ". A recuperar estado por TCP...`
       2. Pede por TCP ao servidor (`ESTADO <id>` ou `CONSULTAR <id>`).
       3. Mostra o estado atual recuperado ao utilizador.
       4. Atualiza `ultimoSeqVisto` para a sequência atual devolvida pelo servidor e continua a acompanhar os avisos seguintes sem duplicar.

---

## PASSO 7: Consola de Administração por Java RMI (`C1`)

### O que fazer:
1. **Criar a interface `AdminRMI.java`:**
   ```java
   import java.rmi.Remote;
   import java.rmi.RemoteException;
   import java.util.List;

   public interface AdminRMI extends Remote {
       List<String> listarLeiloes() throws RemoteException;
       List<String> listarUtilizadoresOnline() throws RemoteException;
       String cancelarLeilao(int id) throws RemoteException;
   }
   ```
2. **Implementar e exportar no `Servidor.java`:**
   - Importante: guardar a instância de `AdminRMI` num atributo `private static AdminRMI adminStubRef;` na classe `Servidor` para o *Garbage Collector* nunca a apagar!
   - No arranque do servidor:
     ```java
     System.setProperty("java.rmi.server.hostname", "localhost");
     Registry registry = LocateRegistry.createRegistry(portoRmi); // omissão 5051
     registry.rebind("LeiloesAdmin", adminImpl);
     ```
   - Os métodos de `adminImpl` chamam diretamente os métodos `synchronized` do `EstadoServidor` (`listar()`, `listarUtilizadoresOnline()`, `cancelarLeilao(id)`).
3. **Criar `ConsolaAdmin.java`:**
   - Liga-se ao registo RMI (`LocateRegistry.getRegistry(host, portoRmi)`), faz `lookup("LeiloesAdmin")` e aceita comandos por argumentos de linha de comandos (ex: `java ConsolaAdmin localhost 5051 LISTAR`, `UTILIZADORES`, `CANCELAR 1`) **e/ou** menu interativo na consola.
   - Ter suporte direto por argumentos de linha de comandos (`java ConsolaAdmin localhost 5051 CANCELAR 1`) é excelente porque permite dar ao professor um comando único pronto a correr no `README`!

---

## PASSO 8: Preparar os 5 Comandos de Demonstração no `README.md` (Critério G9)

O enunciado exige no ponto 5.1: **"Um comando pronto por cenário que o nível exige (reinício, aviso perdido, fecho, cancelamento e anti-sniping), para o docente os ver a correr depressa."**

Para ganhares os 2 valores limpos no **G9** e impressionares o professor na defesa, podes incluir um pequeno programa auxiliar `DemoCenarios.java` (ou modos no `Cliente`/`ConsolaAdmin`) que executa automaticamente cada cenário com um só comando:

1. **Cenário 1 — Reinício e Recuperação (`historico.log`):**
   - Criar utilizador e leilão + licitar -> parar o servidor (`Ctrl+C`) -> voltar a arrancar `java Servidor` -> correr `CONSULTAR 1` e ver que o leilão, melhor licitação e tempo restante foram repostos.
2. **Cenário 2 — Aviso Multicast Perdido e Recuperação por TCP:**
   - Correr o cliente em modo acompanhamento com `--descartar 2` enquanto são feitas 3 licitações -> ver no ecrã a perda detetada no 2.º aviso e a recuperação automática via TCP no 3.º aviso.
3. **Cenário 3 — Fecho Automático pelo Relógio do Servidor:**
   - Criar um leilão com duração de `5` segundos, fazer uma licitação e aguardar `5` segundos sem enviar mais nada -> ver o servidor fechar sozinho e anunciar o vencedor por multicast.
4. **Cenário 4 — Cancelamento via Consola RMI:**
   - Criar um leilão aberto, licitar, e correr `java ConsolaAdmin localhost 5051 CANCELAR <id>` -> ver o aviso multicast de cancelamento e confirmar com `CONSULTAR <id>` que ficou `estado=FECHADO vencedor=- restante=0`.
5. **Cenário 5 — Anti-sniping:**
   - Criar um leilão com duração de `10` segundos (com janela anti-sniping `N=30`), licitar e ver que o `restante` sobe imediatamente de `<= 10` para `30` segundos, tanto no `CONSULTAR` como no aviso multicast.

---

## PASSO 9: Estrutura Página a Página do Relatório PDF (Máx. 6 Páginas)

Quando o código estiver pronto e testado, escreve o relatório em PDF seguindo exatamente esta divisão (nunca ultrapassar 6 páginas):

- **Página 1 — Identificação, Nível Declarado e Arquitetura Geral:**
  - Nível declarado (**Nível Completo**).
  - Diagrama dos 3 processos (`Servidor`, `Cliente`, `ConsolaAdmin`) e canais (`TCP 5050`, `UDP Multicast 239.255.0.1:6060`, `RMI 5051`).
  - Explicação das *threads* do servidor (`CachedThreadPool` para clientes TCP, `SingleThreadScheduledExecutor` para fecho automático, threads RMI) e das *threads* do cliente (consola/TCP + listener UDP multicast).
- **Página 2 — Extensões ao Protocolo do Anexo A:**
  - Formato exato das mensagens UDP Multicast (campos e exemplos para licitação, fecho e cancelamento).
  - Formato do pedido de recuperação de estado por TCP (`ESTADO <id>` / `CONSULTAR <id>`).
  - Especificação completa da interface Java RMI (`AdminRMI`): assinatura dos métodos, retornos e comportamento de erro.
- **Página 3 — Histórico Persistente e Recuperação no Arranque:**
  - Formato das linhas do ficheiro `historico.log`.
  - Explicação do protocolo *Write-Ahead* (gravar + `flush()` antes de enviar `OK`).
  - Como é lido no arranque, como repõe o estado, como fecha leilões que expiraram durante a paragem (`I4`), e como deteta e ignora uma última linha truncada (`B7`).
- **Página 4 — Concorrência, Região Crítica e Consistência:**
  - Indicar explicitamente o **ficheiro** (`EstadoServidor.java`) e os **métodos** (`licitar`, `cancelarLeilao`, `criarLeilao`, `fecharLeiloesVencidos`) que constituem a região crítica, justificando o uso do monitor intrínseco (`synchronized`).
  - Definir o que significa «aceite» (validada dentro do monitor e persistida em disco antes da resposta `OK`).
  - Explicar o que acontece quando uma licitação TCP e um cancelamento RMI chegam ao mesmo tempo (exclusão mútua no mesmo monitor de `EstadoServidor`: a operação que adquire o *lock* primeiro ganha; se o cancelamento ganhar, a licitação seguinte encontra `estado=FECHADO` e recebe `ERRO FECHADO`).
- **Página 5 — Tratamento de Falhas e Limitações Assumidas:**
  - Tabela de falhas: queda abrupta de um cliente TCP (limpeza da sessão ativa no `finally`), queda e reinício do servidor (recuperação via `historico.log`), perda de datagramas UDP (deteção por `seq > ultimoSeq + 1` e recuperação via TCP).
  - Limitações assumidas (ex.: histórico cresce linearmente sem compactação/snapshots; multicast local à sub-rede/localhost).
- **Página 6 — Tabela de Requisitos Cumpridos e Declaração de Uso de IA:**
  - Tabela resumo de `B1` a `B7`, `I1` a `I4`, `C1` e `C2` (todos cumpridos).
  - **Declaração obrigatória do uso de IA:** indicar de forma transparente onde e para quê foi utilizada IA (ex.: apoio no planeamento da arquitetura, revisão de casos de fronteira do protocolo e estruturação da documentação).

