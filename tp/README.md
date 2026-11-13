# Trabalho Prático de Sistemas Distribuídos (2026/2027) — Leilões em Tempo Real

**Nível declarado:** **Nível Completo** (inclui Nível Base `B1–B7`, Nível Intermédio `I1–I4` e Nível Completo `C1–C2`).  
**Tecnologia:** Java 17+ (apenas biblioteca padrão, interface de consola).

---

## 1. Estrutura do Projeto

```text
tp/
├── Conformidade.java              Verificador oficial do protocolo do Anexo A (G2)
├── README.md                      Instrucoes de compilacao, arranque e cenarios
└── src/
    ├── comum/
    │   ├── AdminRMI.java          Interface remota Java RMI (C1)
    │   └── Mensagens.java         Utilitario de parsing chave=valor
    ├── servidor/
    │   ├── Servidor.java          Ponto de entrada TCP, RMI e temporizador de fecho
    │   ├── EstadoServidor.java    Estado partilhado e regiao critica (synchronized)
    │   ├── AtendedorCliente.java  Protocolo TCP do Anexo A + extensao ESTADO <id>
    │   ├── Leilao.java            Modelo de dados do leilao e formatacao Anexo A
    │   ├── Historico.java         Persistencia Write-Ahead em ficheiro (B7)
    │   └── DifusorMulticast.java  Emissao de avisos UDP multicast (I1)
    ├── cliente/
    │   └── Cliente.java           Consola TCP + multicast em tempo real + recuperacao (I2)
    ├── admin/
    │   └── ConsolaAdmin.java      Consola administrativa separada via Java RMI (C1)
    └── demo/
        └── DemoCenarios.java      Demonstracao rapida (1 comando por cenario)
```

---

## 2. Compilar e Arrancar do Zero

Na pasta `tp/`:

### Compilar (para a pasta `out/`)
```bash
javac --release 17 -encoding UTF-8 -d out -sourcepath src src/comum/*.java src/servidor/*.java src/cliente/*.java src/admin/*.java src/demo/*.java Conformidade.java
```

### Arrancar o Servidor (Terminal 1)
> **Nota Windows PowerShell:** colocar `"-Djava.net.preferIPv4Stack=true"` entre aspas no PowerShell; em Bash/CMD usar sem aspas.

- **Linux / macOS / CMD:**
  ```bash
  java -Djava.net.preferIPv4Stack=true -cp out servidor.Servidor
  ```
- **Windows PowerShell:**
  ```powershell
  java "-Djava.net.preferIPv4Stack=true" -cp out servidor.Servidor
  ```
Por omissão usa: TCP `5050`, RMI `5051`, Multicast `239.255.0.1:6060`, anti-sniping `30 s`, histórico `historico.log`.  
Opções configuráveis: `--porto <n> --rmi <n> --grupo <ip> --udp <n> --antisniping <s> --historico <fich>`.

### Verificar Conformidade Anexo A (Terminal 2, com Servidor a correr)
```bash
java -cp out Conformidade localhost 5050
```
*(Resultado esperado: `RESUMO normal [N]: 9/9   erro e fronteira [E]: 21/21   PASSA`)*

### Arrancar Cliente Interativo
```bash
java "-Djava.net.preferIPv4Stack=true" -cp out cliente.Cliente
```

### Arrancar Consola de Administração RMI (processo separado)
```bash
java -cp out admin.ConsolaAdmin
```
Ou com comando direto:
```bash
java -cp out admin.ConsolaAdmin localhost 5051 LISTAR
java -cp out admin.ConsolaAdmin localhost 5051 UTILIZADORES
java -cp out admin.ConsolaAdmin localhost 5051 CANCELAR 1
```

---

## 3. Um Comando Pronto por Cenário (Avaliação Rápida)

Com o `Servidor` a correr nas portas por omissão (`5050`, `5051`, `239.255.0.1:6060`):

### Cenário 1 — Reinício e Recuperação de Estado (`B7` e `I4`)
1. Preparar utilizadores, leilão e licitações antes de parar:
   ```bash
   java "-Djava.net.preferIPv4Stack=true" -cp out demo.DemoCenarios reinicio-prep
   ```
2. Parar o `Servidor` (`Ctrl+C`) e voltar a arrancá-lo:
   ```bash
   java "-Djava.net.preferIPv4Stack=true" -cp out servidor.Servidor
   ```
3. Confirmar com o `cliente.Cliente` (`CONSULTAR <id>` indicado no passo 1) que utilizadores, leilões e melhores valores foram repostos.

### Cenário 2 — Aviso Multicast Perdido e Recuperação por TCP (`I1` e `I2`)
Executa 1 cliente ouvinte com simulação de perda (`1` em cada `2` avisos descartado), realiza 3 licitações, deteta a falha na sequência (`seq=2` perdida -> chega `seq=3`) e recupera o estado atual via TCP (`ESTADO <id>`):
```bash
java "-Djava.net.preferIPv4Stack=true" -cp out demo.DemoCenarios aviso-perdido
```
*(Em alternativa, com `cliente.Cliente` interativo: `java "-Djava.net.preferIPv4Stack=true" -cp out cliente.Cliente --acompanhar <id> --descartar 2`)*

### Cenário 3 — Fecho Automático pelo Relógio do Servidor (`I3` e `B6`)
Cria um leilão de 3 segundos e aguarda o fecho automático pelo servidor (atraso $\le 1\text{ s}$) com difusão do aviso multicast `tipo=FECHO`:
```bash
java "-Djava.net.preferIPv4Stack=true" -cp out demo.DemoCenarios fecho
```

### Cenário 4 — Cancelamento via Consola Java RMI (`C1`)
Cria um leilão, regista uma licitação, lista utilizadores online via RMI, cancela o leilão via RMI (`estado=FECHADO`, `vencedor=-`, `restante=0`), mostra o aviso multicast `tipo=CANCELADO` e prova que licitações seguintes são rejeitadas (`ERRO FECHADO`):
```bash
java "-Djava.net.preferIPv4Stack=true" -cp out demo.DemoCenarios cancelamento
```

### Cenário 5 — Anti-sniping (`C2`)
Cria um leilão de 8 segundos (dentro da janela de 30 s), aguarda 2 segundos (`restante=6`), envia uma licitação e mostra o adiamento do fecho para `30 s` tanto no aviso multicast (`adiado=true restante=30`) como no `CONSULTAR`:
```bash
java "-Djava.net.preferIPv4Stack=true" -cp out demo.DemoCenarios anti-sniping
```

### Cenário Extra — Concorrência de Licitações (`B5` e `G1`)
Lança 20 threads simultâneas a licitar no mesmo leilão e verifica 0 perdas e 0 sobreposições:
```bash
java "-Djava.net.preferIPv4Stack=true" -cp out demo.DemoCenarios concorrencia
```

---

## 4. Limpar Ficheiros Gerados Antes de Empacotar o ZIP

Antes de criar o ficheiro `.zip` para entrega no Moodle (`<num_aluno>.zip`), remover a pasta `out/` e eventuais `*.log`, mantendo `src/`, `Conformidade.java`, `README.md` e `Relatorio.pdf`.
