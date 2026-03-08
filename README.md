# DepChain

## Requirements

- Java (JDK) 17+
- Maven 3.8+
- Docker 24+
- tmux

All commands should be run inside the `/DepChain` directory.

---

## Unit Tests

- `AuthPerfLinkTest` - message authentication, delivery guarantees, and replay/drop handling at the network layer.
- `ConsensusTest` - six sections covering the HotStuff protocol:
  1. NEW\_VIEW collection - quorum threshold and highest-QC selection.
  2. Vote accumulation - full PREPARE -> PRE\_COMMIT -> COMMIT -> DECIDE pipeline.
  3. View change - stale vote rejection after the view advances, replica ignores PREPARE from a crashed leader.
  4. Safety - conflicting node pruned from the local tree after a competing node is decided.
  5. Byzantine - PRE\_COMMIT for an unknown node rejected; DECIDE with forged QC signature rejected; PREPARE failing safe-node check rejected; replayed DECIDE from old view ignored.
  6. Future message buffering - out-of-order messages buffered and replayed in protocol order when the view advances, messages beyond the lookahead window dropped.

```bash
mvn test
```

---

## Manual Tests

The `init.sh` script automates the full setup: it tears down any previous containers, builds the Docker image, starts the containers, and opens a tmux session with one pane per replica and one pane for the client.

```bash
chmod +x init.sh
bash init.sh
```

Once the session is open, use the client menu to append messages to the chain:

```
1. Append a message
2. Exit
```

### Byzantine Test Flags

Each replica accepts an optional second argument that selects its Byzantine behaviour. The flag is passed as the second value in `-Dexec.args`:

```
-Dexec.args="<replicaId> <byzantineMode>"
```

- `0` honest - default, follows the protocol correctly.
- `1` silent - drops all outgoing messages; the replica never responds.
- `2` conflicting - sends conflicting proposals to different replicas.
- `3` wrongNode - casts votes for a node different from the proposed one.
- `4` wrongLeader - sends vote messages to the wrong leader.
- `5` doubleVote - sends the same vote twice in the same phase.

To test Byzantine behaviour, change the second argument of the desired replica in `init.sh` to the corresponding flag before running the script.

The system tolerates up to f Byzantine replicas where n >= 3f + 1. With the default four replicas, one Byzantine replica can be tolerated.
