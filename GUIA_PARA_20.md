# Guia Completo para Tirar 20/20 — Trabalho Prático de Sistemas Distribuídos (2026/2027)
**Tema:** Leilões em Tempo Real  
**Entrega:** 11/12/2026 (Moodle) | **Defesas:** 14 a 18/12/2026  

---

## 1. Como funciona a nota para chegar aos 20 valores

Para atingir **20 valores**, tens de implementar o **Nível Completo** (que inclui obrigatoriamente todos os requisitos do **Nível Base** + **Nível Intermédio** + **Nível Completo**) e cumprir os **10 critérios de avaliação (G1 a G10)**, onde cada critério vale **2 valores**:

| Critério | Cotação | Nível | O que o professor vai verificar na prática |
| :--- | :---: | :---: | :--- |
| **G1. Concorrência e atomicidade** | 2,0 val | Base / Completo | Passar no teste de concorrência do docente; região crítica e estado partilhado sempre protegidos (incluindo licitação TCP simultânea com cancelamento RMI). |
| **G2. Protocolo** | 2,0 val | Base | Passar **30/30** no `Conformidade.java`; extensões documentadas sem alterar o Anexo A; mensagens inválidas nunca derrubam o servidor. |
| **G3. Registo e autenticação** | 2,0 val | Base | Palavra-passe guardada apenas como hash **SHA-256** (nunca em claro em ficheiros, histórico ou logs); validação rigorosa de sessão. |
| **G4. Histórico e recuperação** | 2,0 val | Base | Gravar no ficheiro **antes** de responder `OK`; parar e reiniciar o servidor repõe todo o estado; última linha truncada não impede o arranque. |
| **G5. Difusão e perdas** | 2,0 val | Intermédio | Avisos UDP multicast com n.º de sequência por leilão; cliente deteta falha na sequência, recupera estado via TCP e tem flag para descartar 1 em cada $N$ avisos. |
| **G6. Tempo e fecho** | 2,0 val | Base / Intermédio | Relógio exclusivo do servidor; recusa licitações tardias; fecho automático (atraso $\le 1\text{ s}$) com anúncio do vencedor; fecha leilões vencidos ao arrancar. |
| **G7. Administração e anti-sniping** | 2,0 val | Completo | Consola Java RMI em processo separado (listar leilões, listar utilizadores online, cancelar leilão); *anti-sniping* nos últimos $N$ segundos. |
| **G8. Qualidade do código** | 2,0 val | Todos | Java 17+ (só biblioteca padrão); código limpo e organizado; erros tratados; sockets, ficheiros e threads fechados sem fugas de recursos. |
| **G9. Relatório e README** | 2,0 val | Todos | Compilar e correr do zero em minutos; 1 comando pronto por cenário de teste; Relatório PDF (máx. 6 páginas) com todas as justificações exigidas. |
| **G10. Defesa (Individual)** | 2,0 val | Todos | Explicar o código entregue (especialmente o percurso completo de uma licitação) e responder a perguntas *"e se..."* do docente. |

> **Regra de ouro do enunciado:** *"Um requisito só conta quando o docente o vê a funcionar."*

---

## 2. Checklist Técnica de Todos os Requisitos (B1 a C2)

### Nível Base (requisitos B1 a B7)
- [ ] **B1 — Servidor TCP concorrente:**
  - Porto configurável por argumento (por omissão `5050`; nunca usar `5000`).
  - Usar uma *thread por cliente* ou `Executors.newCachedThreadPool()` (nunca usar uma *thread pool* fixa pequena, pois o teste do professor com muitos clientes em simultâneo vai bloquear).
- [ ] **B2 — Protocolo do Anexo A cumprido à letra:**
  - Codificação `UTF-8`, mensagens terminadas em `\n` (aceitar `\r\n` na leitura e enviar sempre `\n`).
  - Passar nos **30 testes (9 normais `[N]` e 21 de erro/fronteira `[E]`)** do verificador público `Conformidade.java`.
- [ ] **B3 — Registo e Autenticação (`REGISTAR` e `LOGIN`):**
  - Guardar apenas o hash **SHA-256** (`MessageDigest.getInstance("SHA-256")`) convertido para hexadecimal/Base64.
  - `REGISTAR` **não** inicia sessão automaticamente.
  - Sem login na ligação atual, o cliente só pode executar `REGISTAR`, `LISTAR`, `CONSULTAR` e `SAIR`.
- [ ] **B4 — Operações sobre leilões (`CRIAR`, `LISTAR`, `CONSULTAR`, `LICITAR`, `SAIR`):**
  - Formato exato das respostas de sucesso e erro.
  - `LISTAR` devolve `OK <n>` seguido de `<n>` linhas `LEILAO <registo>` ordenadas por **ID crescente**.
- [ ] **B5 — Licitação atómica:**
  - Duas ou mais licitações concorrentes ao mesmo leilão têm de ser processadas em exclusão mútua (uma após a outra): nenhuma se perde, nenhuma se sobrepõe e a segunda é avaliada contra o estado deixado pela primeira.
- [ ] **B6 — Rejeição de licitação em leilão fechado:**
  - Se `hora_atual_servidor >= hora_fecho` (ou se o leilão foi cancelado), qualquer `LICITAR` devolve `ERRO FECHADO <motivo>`.
- [ ] **B7 — Histórico persistente em ficheiro (Write-Ahead Log):**
  - Sem base de dados (ficheiro de texto ou binário próprio).
  - Gravar **antes** de responder `OK` ao cliente (com `flush()`): registos de utilizadores (com hash SHA-256), criação de leilões, licitações aceites, fechos, cancelamentos e adiamentos de hora de fecho (*anti-sniping*).
  - No arranque, o servidor lê o ficheiro e reconstrói o estado completo.
  - **Tolerância a falhas:** se a última linha do ficheiro estiver incompleta/truncada (porque o servidor caiu a meio de uma escrita), o servidor ignora essa linha truncada e arranca normalmente sem falhar.

---

### Nível Intermédio (requisitos I1 a I4)
- [ ] **I1 — Difusão Multicast UDP com número de sequência por leilão:**
  - Grupo e porto UDP configuráveis (por omissão `239.255.0.1` e porto `6060`; usar `-Djava.net.preferIPv4Stack=true`).
  - Cada leilão tem o seu próprio contador de sequência para avisos (`seq = 1, 2, 3, ...`), incrementado a cada **licitação aceite**, **fecho** ou **cancelamento**.
  - O datagrama multicast desenhado pelo grupo tem de incluir pelo menos o `id` do leilão, o número de sequência `seq`, o tipo de evento (licitação / fecho / cancelamento) e os dados relevantes (valor, licitador/vencedor, nova hora/restante se houver *anti-sniping*).
- [ ] **I2 — Acompanhamento em tempo real, deteção e recuperação de perdas:**
  - O cliente junta-se ao grupo multicast para acompanhar leilões em tempo real.
  - Guarda o `ultimoSeqVisto` para o leilão que acompanha.
  - Se receber um aviso com `seq > ultimoSeqVisto + 1`, deteta que houve perda de pacotes UDP.
  - **Recuperação:** faz imediatamente um pedido por TCP ao servidor (pode usar `CONSULTAR <id>`, onde `licitacoes` mais o estado `FECHADO` indicam a sequência, ou um comando próprio de extensão como `ESTADO <id>`), mostra o estado atual recuperado, atualiza `ultimoSeqVisto` para a sequência atual e continua a exibir os avisos seguintes sem duplicados.
  - **Simulação obrigatória:** o cliente tem um parâmetro/opção (ex: `--descartar N`) que descarta artificialmente `1` em cada `N` datagramas UDP recebidos, para provar na defesa que a deteção e recuperação funcionam.
- [ ] **I3 — Fecho automático pelo servidor:**
  - Uma tarefa periódica ou agendada no servidor (`ScheduledExecutorService`) verifica os leilões abertos e, à hora de fecho (atraso máximo de **1 segundo**), fecha o leilão, grava o fecho no histórico e envia o aviso multicast anunciando o vencedor (ou `-` se não houve licitações).
- [ ] **I4 — Fecho de leilões expirados durante paragem do servidor:**
  - Quando o servidor arranca e termina de carregar o histórico, verifica imediatamente se algum leilão que estava `ABERTO` já ultrapassou a sua hora de fecho enquanto o servidor esteve desligado; se sim, fecha-o logo, grava no histórico e envia o aviso multicast de fecho.

---

### Nível Completo (requisitos C1 e C2)
- [ ] **C1 — Consola de Administração por Java RMI (processo separado):**
  - O servidor cria o registo RMI no próprio processo (`LocateRegistry.createRegistry(portoRmi)`, ex: `5051`) e mantém uma **referência forte** (ex: atributo `static` ou de instância no servidor) ao objeto remoto exportado para evitar que o *Garbage Collector* o apague (`NoSuchObjectException`).
  - Usar `-Djava.rmi.server.hostname=localhost` no arranque do servidor.
  - A consola RMI (sem guardar estado próprio) permite 3 operações remotas:
    1. **Listar todos os leilões** (estado atual).
    2. **Listar os utilizadores com sessão TCP iniciada** no momento.
    3. **Cancelar um leilão aberto** (`id`):
       - Fecha o leilão imediatamente **sem vencedor** (`estado=FECHADO`, `vencedor=-`, `restante=0`, mantendo `melhor` e `licitacoes` como estavam).
       - Grava o cancelamento no histórico antes de confirmar à consola (e repõe-se como cancelado se o servidor reiniciar).
       - Gera aviso multicast de fecho/cancelamento (incrementando o número de sequência do leilão).
       - **Atomicidade crítica:** as threads RMI têm de adquirir exatamente o mesmo *lock*/monitor que as threads TCP usam para `LICITAR`. Depois de confirmado o cancelamento, nenhuma licitação é aceite; e nenhuma licitação que já recebeu `OK` pode ser perdida.
- [ ] **C2 — Anti-sniping:**
  - Parâmetro configurável no servidor: janela $N$ segundos (por omissão `30` s).
  - Se uma licitação válida for aceite quando faltam $\le N$ segundos para o fecho (`hora_fecho - agora <= N`), a nova hora de fecho passa a ser `agora + N` (calculada pelo relógio do servidor; nunca antecipa o fecho se faltarem mais de $N$ segundos).
  - A nova hora de fecho é **gravada no histórico** (para ser reposta se o servidor reiniciar), anunciada no aviso multicast (sem gastar outro número de sequência se for incluída no próprio aviso da licitação), atualiza o agendamento do fecho automático (`I3`) e reflete-se no campo `restante` do `CONSULTAR` e `LISTAR`.

---

## 3. Armadilhas do `Conformidade.java` e do Anexo A (Atenção Máxima!)

O docente vai correr o ficheiro `Conformidade.java` diretamente contra o teu servidor para atribuir os **2 valores do critério G2**. Analisando o código do `Conformidade.java`, estas são as regras onde muitos grupos perdem pontos:

1. **Ordem Estrita de Verificação de Erros (Anexo A.3):**
   Tens de validar exatamente por esta ordem e devolver o **primeiro** erro que se aplica:
   - **`CRIAR` e `LICITAR`:** `SINTAXE` $\rightarrow$ `SEM_SESSAO` $\rightarrow$ `VALOR` $\rightarrow$ `INEXISTENTE` $\rightarrow$ `FECHADO` $\rightarrow$ `PROPRIO` $\rightarrow$ `BAIXA`
     - *Exemplo testado na linha 380:* enviar `LICITAR abc 5` **sem login** tem de devolver `ERRO SEM_SESSAO` (e **não** `ERRO VALOR`!).
     - *Exemplo testado nas linhas 366-367:* o próprio criador licitar num leilão já fechado, ou alguém licitar valor baixo num leilão já fechado, tem de devolver `ERRO FECHADO` (e **não** `PROPRIO` nem `BAIXA`!).
   - **`LOGIN`:** `SINTAXE` $\rightarrow$ `JA_AUTENTICADO` $\rightarrow$ `CREDENCIAIS`
   - **`REGISTAR`:** `SINTAXE` $\rightarrow$ `VALOR` $\rightarrow$ `EXISTE`
   - **`LISTAR`, `CONSULTAR`, `SAIR`:** `SINTAXE` $\rightarrow$ `VALOR` $\rightarrow$ `INEXISTENTE`

2. **Linhas com mais de 1024 caracteres (linha 243 do `Conformidade.java`):**
   - Se chegar uma linha com `length() > 1024` (antes do `\n`), o servidor responde `ERRO SINTAXE <motivo>` e **mantém a ligação aberta** para os comandos seguintes. Cuidado ao ler do socket para consumir até ao `\n` da linha gigante antes de processar o comando seguinte (`LISTAR`).

3. **Espaços e Parsing do `CRIAR` (linhas 236-242 e 268-281 do `Conformidade.java`):**
   - Espaço no início da linha (`" LISTAR"`), espaço no fim (`"LISTAR "`) ou dois espaços seguidos entre argumentos (`"CONSULTAR  1"`) dão sempre `ERRO SINTAXE`.
   - No `CRIAR <base> <incremento> <duração> <artigo>`:
     - Se faltarem campos (ex: `"CRIAR 100 5 60"` sem espaço depois do `60`), dá `ERRO SINTAXE`.
     - Tudo o que está após o **4.º espaço** é o `artigo`. Se o `artigo` for vazio (`"CRIAR 100 5 60 "`), começar com espaço (`"CRIAR 100 5 60  x"`), terminar com espaço (`"CRIAR 100 5 60 x "`), tiver mais de 60 caracteres ou tiver caracteres de controlo, dá **`ERRO VALOR`** (e não `SINTAXE`!).

4. **Intervalos dos Campos (`VALOR`):**
   - `utilizador`: 3 a 20 caracteres, apenas `[A-Za-z0-9_]`.
   - `palavra-passe`: 1 a 64 caracteres, sem espaços nem caracteres de controlo (`<= 31` ou `127`).
   - Números: inteiros decimais sem sinal, 1 a 9 dígitos (`[0-9]{1,9}`, zeros à esquerda são aceites).
     - `id` e `incremento`: `1` a `999_999_999`
     - `base` e `valor`: `0` a `999_999_999`
     - `duração`: `1` a `86_400`

5. **Arredondamento de `restante` para cima (linha 384 do `Conformidade.java`):**
   - O campo `restante` (em segundos) tem de ser **arredondado para cima**:
     ```java
     long msRestantes = horaFechoMillis - System.currentTimeMillis();
     long restanteSeg = msRestantes <= 0 ? 0 : (long) Math.ceil(msRestantes / 1000.0);
     ```
   - Um leilão de `60` segundos consultado `0,3` segundos depois de ser criado tem `59,7` s restantes, pelo que **tem de devolver `restante=60`** (se usasses divisão inteira daria `59` e falharia no teste!).

6. **Formato exato do `<registo>` em `CONSULTAR` e `LISTAR`:**
   - Todos os 10 campos na mesma linha, exatamente por esta ordem:
     ```text
     id=<id> estado=<ABERTO|FECHADO> melhor=<0|val> vencedor=<-|user> criador=<user> base=<base> incremento=<inc> licitacoes=<n> restante=<seg> artigo=<artigo>
     ```

---

## 4. Arquitetura Recomendada e Roteiro de Desenvolvimento

### Estrutura de Ficheiros Sugerida (Java 17 Puro)
Para manter o código limpo (Critério **G8**) e fácil de compilar sem IDE:
- `Servidor.java` — ponto de entrada (`main`), arranque do socket TCP, registo RMI e `ScheduledExecutorService` para fechos automáticos.
- `EstadoServidor.java` — classe central com o estado partilhado (utilizadores, leilões, sessões ativas) onde estão os métodos da **região crítica** (`synchronized` ou `ReentrantLock`) chamados tanto pelas threads TCP como pelas threads RMI e pelo temporizador de fecho.
- `Historico.java` — escrita síncrona (`flush`) no ficheiro de log e leitura/recuperação no arranque (ignorando última linha truncada).
- `AtendedorCliente.java` — `Runnable` que trata uma ligação TCP de um cliente, valida o Anexo A e invoca o `EstadoServidor`.
- `DifusorMulticast.java` — envia os datagramas UDP multicast para o grupo configurado.
- `AdminRMI.java` (interface `Remote`) e `ConsolaAdmin.java` — interface RMI e processo separado da consola de administração.
- `Cliente.java` — aplicação de consola para utilizadores (com thread para ouvir avisos multicast, opção de simular perda de 1 em $N$ avisos e recuperação automática por TCP).

### Roteiro em 4 Fases
1. **Fase 1 (Base TCP + Protocolo):** Implementar `Servidor`, `EstadoServidor` e `AtendedorCliente` em memória; correr `Conformidade.java` até passar **30/30**.
2. **Fase 2 (Persistência + Concorrência):** Adicionar `Historico.java` (gravar antes de `OK`, recuperar no arranque, testar linha truncada no fim do ficheiro) e criar um script/teste com 20 threads a licitar em simultâneo no mesmo leilão.
3. **Fase 3 (Intermédio — Tempo e Multicast):** Adicionar fecho automático a cada 100–200 ms (para garantir atraso $\le 1\text{ s}$), fecho de leilões expirados no arranque, envio de avisos UDP multicast e `Cliente.java` com deteção/recuperação de falhas de sequência.
4. **Fase 4 (Completo — RMI e Anti-sniping):** Implementar o prolongamento de `horaFecho` nos últimos $N$ segundos (`C2`) e criar a `ConsolaAdmin` via Java RMI (`C1`).

---

## 5. O que Entregar para Garantir os 2 Valores em G9 (Relatório + README)

O ficheiro `.zip` entregue no Moodle (`<num_aluno>.zip` ou `<num1>-<num2>.zip`) tem de conter:

### 1. Código-fonte + `README.md`
O `README` tem de incluir:
- Declaração explícita: **"Nível declarado: Nível Completo"**.
- Comando de compilação direto:
  ```bash
  javac --release 17 -encoding UTF-8 *.java
  ```
- Comandos prontos para arrancar o servidor, a consola RMI e os clientes.
- **Um comando pronto por cada um dos 5 cenários obrigatórios** (para o professor copiar, colar e ver a funcionar rapidamente):
  1. **Reinício do servidor** (mostrar que o estado persiste após parar e voltar a arrancar).
  2. **Aviso multicast perdido e recuperado** (cliente a correr com a flag de descartar $1$ em cada $N$ avisos).
  3. **Fecho automático de leilão** (leilão curto, ex: 10 s, a fechar sozinho e anunciar vencedor).
  4. **Cancelamento via Consola RMI** (cancelar leilão aberto e ver aviso multicast + estado `FECHADO` com `vencedor=-`).
  5. **Anti-sniping** (licitar nos últimos $N$ segundos e ver `restante` a subir para $N$).

### 2. Relatório em PDF (Máximo 6 páginas)
Segue exatamente estas secções no PDF para não faltar nada do ponto 5.2 do enunciado:
1. **Extensões ao Protocolo do Anexo A:** formato exato dos datagramas UDP multicast, formato do pedido de recuperação de estado por TCP, e especificação da interface Java RMI (métodos, argumentos, retornos e erros).
2. **Arquitetura do Sistema:** diagrama e explicação dos processos (`Servidor`, `Cliente`, `ConsolaAdmin`), modelo de *threads* (`CachedThreadPool`, thread de fecho periódico, thread de receção multicast no cliente) e estruturas do estado partilhado.
3. **Formato do Ficheiro de Histórico e Recuperação:** explicar a sintaxe de cada linha gravada (registo, criação, licitação, fecho, cancelamento, anti-sniping) e como o parser no arranque deteta e ignora uma última linha truncada.
4. **Decisões de Concorrência e Consistência:**
   - Indicar o **nome exato do ficheiro e do método** onde está a região crítica e justificar a escolha do mecanismo de sincronização.
   - Definir o momento exato em que uma licitação é considerada «aceite» (após validação na região crítica e gravação no ficheiro de histórico, antes de enviar `OK`).
   - Explicar quem ganha quando uma licitação TCP e um cancelamento RMI chegam ao mesmo tempo (ambos disputam o mesmo *lock*; o primeiro a entrar na região crítica executa, e se for o cancelamento, a licitação seguinte vê o estado `FECHADO` e recebe `ERRO FECHADO`).
5. **Tratamento de Falhas:** o que acontece quando um cliente cai abruptamente (`EOF`/exceção no socket limpa a sessão), quando o servidor cai e reinicia, e quando um datagrama UDP se perde na rede.
6. **Limitações Assumidas e Requisitos Cumpridos:** tabela com `B1–B7`, `I1–I4`, `C1–C2` dados como cumpridos e eventuais limitações.
7. **Declaração de Uso de IA:** secção obrigatória indicando onde e para quê foram usadas ferramentas de IA.

---

## 6. Como Preparar a Defesa (Critério G10 — 2 Valores Individuais)

Na defesa (14 a 18 de dezembro), cada elemento do grupo é avaliado individualmente:
1. **Saber explicar o percurso completo de uma licitação no código:**
   - Onde a linha `"LICITAR 1 105\n"` é lida no `BufferedReader` da thread TCP;
   - Como é validada a sintaxe, a sessão e os valores pela ordem do Anexo A;
   - Onde entra na região crítica (`synchronized`);
   - Como verifica se o leilão está aberto pelo relógio do servidor e se ativa o *anti-sniping*;
   - Onde escreve e faz `flush()` no ficheiro de histórico **antes** de devolver `OK n=1`;
   - Onde liberta o *lock*, envia a resposta `OK n=1` pelo socket TCP e dispara o datagrama UDP multicast com o novo número de sequência.
2. **Perguntas clássicas *"E se..."* do professor para estares preparado:**
   - *"E se o servidor morrer logo depois de escrever no histórico, mas antes de enviar `OK` ao cliente?"* (A licitação ficou aceite e persiste no arranque; o cliente vê a ligação cair e ao reconectar/consultar vê a sua licitação lá).
   - *"E se a consola RMI cancelar o leilão no exato milissegundo em que um cliente está a licitar?"* (Explicar que o método RMI de cancelar usa o mesmo monitor/lock que o método de licitar).
   - *"E se se perderem 3 avisos multicast seguidos, incluindo o aviso de fecho?"* (Explicar como o cliente deteta o salto no número de sequência ou faz recuperação por TCP e descobre que o estado passou a `FECHADO`).

