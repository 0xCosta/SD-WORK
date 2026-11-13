# Rascunho Base para o Relatório PDF (Máximo 6 Páginas)

> **Nota:** Copia/adapta este conteúdo para Word/LaTeX/Markdown-to-PDF, adiciona os teus dados de aluno(s) e **confirma que o PDF final tem no máximo 6 páginas**.

---

# Sistema Distribuído de Leilões em Tempo Real

- **Unidade Curricular:** Sistemas Distribuídos (LEI / LSIRC) — 2026/2027  
- **Docente:** Ricardo Costa  
- **Autor(es):** `<Nome Completo> (<Número de Aluno>)`  
- **Nível Declarado:** **Nível Completo** (`B1–B7`, `I1–I4`, `C1–C2`)

---

## 1. Arquitetura do Sistema e Relação com os Conceitos da UC

O sistema adota uma arquitetura híbrida centrada num servidor autoritativo, combinando três modelos de comunicação estudados na UC:

1. **Cliente-Servidor TCP (`pedido/resposta`, cliente leve):** Os processos `Cliente` funcionam como *clientes leves* (apenas interface de consola e receção de avisos), mantendo todo o processamento, validação temporal e persistência no `Servidor` (T03, slides 9–10). A comunicação usa sockets TCP em UTF-8, cumprindo à letra o Anexo A.
2. **Publicação-Subscrição baseada em eventos (UDP Multicast):** Para difundir licitações aceites, fechos e cancelamentos a múltiplos clientes interessados sem acoplamento referencial entre emissor e recetores (T03, slide 7), o servidor envia datagramas UDP para o grupo configurável `239.255.0.1:6060`. Como este canal é desacoplado e não fiável (*best-effort*), o protocolo incorpora números de sequência por leilão e recuperação por TCP.
3. **Estilo baseado em objetos (Java RMI):** A `ConsolaAdmin`, num processo independente, invoca métodos remotos sobre o objeto `AdminRMI` exportado pelo servidor num registo RMI dedicado (porto `5051`), ocultando os detalhes da rede através de *middleware* Java RMI (T03, slides 5 e 18).

### Processos, Threads e Estado Partilhado
- **Processo `Servidor` (`src/servidor/Servidor.java`):**
  - *Thread* principal: aceita ligações TCP em `ServerSocket` e submete cada cliente a um `ExecutorService` criado com `Executors.newCachedThreadPool()` (`B1`), evitando bloqueios quando muitos clientes ligam em simultâneo.
  - *Threads* atendedoras TCP (`AtendedorCliente`): uma por ligação ativa, trata os pedidos pela ordem de chegada no socket e limpa a sessão no bloco `finally`.
  - *Thread* de fecho automático (`ScheduledExecutorService` com `FechoAutomatico`): executa `fecharLeiloesVencidos()` a cada `150 ms`, garantindo fecho até `1 s` após a hora limite (`I3`).
  - *Threads* do runtime Java RMI: executam chamadas remotas da `ConsolaAdmin` sobre `AdminRMIImpl`.
- **Processo `Cliente` (`src/cliente/Cliente.java`):**
  - *Thread* principal: lê comandos do utilizador e envia-os sincronamente por TCP.
  - *Thread daemon* `MulticastListener`: recebe datagramas UDP do grupo multicast, filtra pelos leilões acompanhados, deteta falhas de sequência e abre uma ligação TCP curta para recuperar o estado via `ESTADO <id>`.
- **Processo `ConsolaAdmin` (`src/admin/ConsolaAdmin.java`):** processo separado sem estado local (`C1`), que executa `LISTAR`, `UTILIZADORES` e `CANCELAR <id>` via RMI.
- **Estado Partilhado (`src/servidor/EstadoServidor.java`):** composto por `utilizadores` (`HashMap<String, String>` com hash SHA-256), `leiloes` (`TreeMap<Integer, Leilao>` ordenado por ID crescente) e `sessoesAtivas` (`TreeMap<String, Integer>` com contagem de sessões por utilizador).

---

## 2. Extensões ao Protocolo do Anexo A

Os sete comandos do Anexo A (`REGISTAR`, `LOGIN`, `CRIAR`, `LISTAR`, `CONSULTAR`, `LICITAR`, `SAIR`) mantêm-se inalterados. Foram definidas três extensões:

### 2.1 Aviso Multicast UDP (`DifusorMulticast.java`)
Formato textual UTF-8 de uma linha com pares `chave=valor` separados por um espaço:
- **Licitação aceite (inclui sinalização de anti-sniping sem gastar outro `seq`):**  
  `AVISO id=<id> seq=<seq> tipo=LICITACAO valor=<melhor> licitador=<user> restante=<seg> adiado=<true|false>`
- **Fecho normal (automático ou no arranque):**  
  `AVISO id=<id> seq=<seq> tipo=FECHO melhor=<melhor> vencedor=<user|-> licitacoes=<n>`
- **Cancelamento administrativo (RMI):**  
  `AVISO id=<id> seq=<seq> tipo=CANCELADO melhor=<melhor> vencedor=- licitacoes=<n>`

Cada leilão mantém o seu próprio contador `sequenciaAviso` (`1, 2, 3, ...`), incrementado atomicamente por cada licitação aceite, fecho ou cancelamento. Em caso de *anti-sniping*, o prolongamento é comunicado no mesmo aviso da licitação (`adiado=true` e novo `restante`), sem consumir número de sequência adicional.

### 2.2 Pedido de Estado por TCP (`ESTADO <id>`)
- **Pedido:** `ESTADO <id>\n` (não exige sessão iniciada).
- **Ordem de erros:** `SINTAXE` $\rightarrow$ `VALOR` $\rightarrow$ `INEXISTENTE`.
- **Resposta OK:**  
  `OK seq=<sequenciaAviso> cancelado=<true|false> <registo_do_Anexo_A>`  
Permite ao cliente sincronizar o número de sequência atual quer o último evento tenha sido uma licitação, um fecho ou um cancelamento.

### 2.3 Interface Remota Java RMI (`AdminRMI.java`)
Serviço registado como `"LeiloesAdmin"` no porto `5051` (com `-Djava.rmi.server.hostname=localhost` e referência estática `adminServicoRef` em `Servidor.java` para impedir recolha pelo *Garbage Collector*):
- `List<String> listarLeiloes() throws RemoteException` — devolve todos os leilões por ordem crescente de `id`, incluindo `seq` e `cancelado`.
- `List<String> listarUtilizadoresOnline() throws RemoteException` — devolve os nomes dos utilizadores com pelo menos uma sessão TCP ativa.
- `String cancelarLeilao(int id) throws RemoteException` — devolve `OK leilao <id> cancelado (seq=<seq>)` ou `ERRO INEXISTENTE` / `ERRO FECHADO` / `ERRO INTERNO`.

---

## 3. Formato do Ficheiro de Histórico e Recuperação

O ficheiro `historico.log` funciona como um *Write-Ahead Log* em texto UTF-8, com campos separados por tabulação (`\t`) e terminador explícito `END\n`:
- `USER\t<utilizador>\t<sha256_hex>\tEND`
- `CRIAR\t<id>\t<criador>\t<base>\t<incremento>\t<horaFechoMillis>\t<artigo>\tEND`
- `LICITAR\t<id>\t<licitador>\t<valor>\t<numOrdem>\t<novaHoraFechoMillis>\t<seqAviso>\tEND`
- `FECHAR\t<id>\t<vencedor>\t<seqAviso>\tEND`
- `CANCELAR\t<id>\t<seqAviso>\tEND`

A escolha de `\t` é segura porque o Anexo A proíbe caracteres de controlo em todos os campos (incluindo `artigo`). As palavras-passe nunca são escritas em claro: apenas o resumo SHA-256 (`EstadoServidor.sha256`).

### Recuperação no Arranque e Tolerância a Linha Truncada (`B7`, `I4`)
No método `Historico.carregarEAbrir(EstadoServidor)`, o ficheiro é lido por linhas em bytes UTF-8 mantendo o *offset* do último registo válido (`ultimoOffsetValido`):
1. Cada linha tem de terminar em `\n`, descodificar corretamente em UTF-8, terminar no campo `END` e possuir o número exato de colunas do seu tipo.
2. Se a **última linha** estiver incompleta (o servidor caiu a meio da escrita), o parser ignora-a, emite um aviso e trunca o ficheiro em `ultimoOffsetValido` via `FileChannel.truncate`, impedindo que novas escritas se colem à linha partida.
3. Logo após carregar o histórico, `Servidor.main` invoca `estado.fecharLeiloesVencidos()` (`I4`), fechando, gravando e anunciando por multicast todos os leilões cuja `horaFechoMillis` já foi ultrapassada durante a paragem.

---

## 4. Decisões de Concorrência, Tempo e Consistência

### Região Crítica
Toda a região crítica encontra-se centralizada no ficheiro `src/servidor/EstadoServidor.java`, protegida pelo monitor intrínseco da instância única (`synchronized` nos métodos `registar`, `login`, `terminarSessao`, `criarLeilao`, `listar`, `consultar`, `estadoEstendido`, `licitar`, `fecharLeiloesVencidos`, `adminListarLeiloes`, `adminListarUtilizadoresOnline` e `adminCancelarLeilao`).  
**Justificação:** Embora um bloqueio por leilão aumentasse o paralelismo entre leilões distintos, um monitor único sobre `EstadoServidor` evita *deadlocks* com o mapa global `leiloes`/`sessoesAtivas`, simplifica a verificação da consistência em `LISTAR` e garante ordem total entre operações TCP, RMI e o temporizador de fecho, mantendo secções críticas curtas.

### Consistência: O que significa «Aceite» e Corrida Licitação vs. Cancelamento
1. **Licitação «aceite»:** uma licitação só passa a aceite após passar todas as validações pela ordem do Anexo A dentro do monitor `synchronized`, atualizar a nova hora de fecho (se *anti-sniping*) e concluir a gravação com `flush()` em `historico.log`. Só depois o servidor atualiza a memória, envia o datagrama multicast em ordem de `seq` e responde `OK n=<k>` ao cliente. Se ocorrer falha de escrita em disco, a memória não é alterada e o cliente recebe `ERRO INTERNO`.
2. **Licitação TCP vs. Cancelamento RMI em simultâneo:** ambos adquirem o mesmo monitor em `EstadoServidor`.
   - Se `licitar` entrar primeiro: grava `LICITAR`, envia multicast `seq=k` e devolve `OK`; em seguida `adminCancelarLeilao` grava `CANCELAR`, marca `fechado=true`, `vencedor=-` (`melhor` e `licitacoes` preservados conforme Anexo A) e envia multicast `seq=k+1`.
   - Se `adminCancelarLeilao` entrar primeiro: grava `CANCELAR`, envia multicast e confirma à consola RMI; quando `licitar` entra a seguir, encontra `l.fechado == true` e devolve `ERRO FECHADO`.

### Decisões de Tempo (`B6`, `I3`, `C2`)
Como não existe relógio global num sistema distribuído (T01, slide 15), **apenas o relógio do servidor (`System.currentTimeMillis()`) decide**. Os clientes apenas recebem `duracao` (na criação) e `restante` (arredondado para cima em `Leilao.calcularRestanteSeg`). Em `C2`, se `horaFechoMillis - agora <= N * 1000`, `horaFechoMillis` passa a `agora + N * 1000`, sendo persistida em `LICITAR`.

---

## 5. Tratamento de Falhas e Limitações Assumidas

| Cenário de Falha | Comportamento do Sistema |
| :--- | :--- |
| **Queda abrupta de um cliente TCP** | `AtendedorCliente` captura `EOF`/`IOException` e remove a sessão em `finally` (`terminarSessao`), refletindo-se logo em `ConsolaAdmin`. |
| **Queda ou reinício do servidor** | Estado reconstruído a partir de `historico.log` até ao último registo íntegro; leilões expirados durante a paragem são fechados e difundidos (`I4`). |
| **Perda de avisos UDP multicast** | `Cliente` compara `seq` com `ultimoSeqVisto`: se `seq > ultimoSeqVisto + 1`, deteta perda e recupera por TCP via `ESTADO <id>`; pacotes `seq <= ultimoSeqVisto` são ignorados (sem duplicados). |
| **Mensagem TCP mal formada ou > 1024 chars** | `AtendedorCliente` drena até `\n`, responde `ERRO SINTAXE` sem fechar o socket nem derrubar o servidor. |

**Limitações assumidas:**
- O ficheiro `historico.log` cresce por *append* sem compactação automática (*snapshots*).
- A difusão multicast assume uma rede local ou `localhost` que suporte tráfego IPv4 multicast (`239.255.0.1`).
- Caso se perca **apenas** o último aviso de um leilão (sem novos avisos posteriores desse leilão), a deteção reativa baseada em sequência não dispara até existir novo evento ou consulta explícita.

---

## 6. Requisitos Cumpridos e Declaração de Uso de IA

### Requisitos Cumpridos
- **Nível Base:** `B1` (TCP concorrente), `B2` (Anexo A: 30/30 no `Conformidade.java`), `B3` (SHA-256 e sessões), `B4` (operações de leilão), `B5` (atomicidade em concorrência), `B6` (rejeição tardia), `B7` (histórico com tolerância a linha truncada).
- **Nível Intermédio:** `I1` (multicast UDP sequenciado), `I2` (acompanhamento, `--descartar N`, deteção e recuperação TCP), `I3` (fecho automático $\le 1\text{ s}$), `I4` (fecho de leilões vencidos no arranque).
- **Nível Completo:** `C1` (Consola Java RMI em processo separado com cancelamento atómico), `C2` (anti-sniping configurável e persistido).

### Declaração de Uso de Inteligência Artificial
*(Adaptar honestamente ao que foi efetivamente usado pelo grupo)*  
Foram utilizadas ferramentas de IA (assistente de programação) como apoio ao planeamento modular das classes Java, revisão dos casos de fronteira do protocolo do Anexo A e verificação de cenários de concorrência e recuperação de falhas. Todo o código, estrutura de persistência e decisões arquiteturais foram revistos, testados e validados pelo(s) elemento(s) do grupo, garantindo domínio integral para a defesa oral (`G10`).
