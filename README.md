# DepChain

## Requirements

- Java (JDK) 17+
- Maven 3.8+
- Docker 24+
- tmux

All commands should be run inside the `/DepChain` directory.

---

## Unit Tests

- `AuthPerfLinkTest` - Tests the Authenticated Perfect Links implementation for message authentication, delivery guarantees, and replay/drop handling at the network layer. The following scenarios are covered:
  1. Bidirectional Communication: Verifies that messages can be sent and received in both directions.
  2. Reliable Delivery (Single Message): Ensures that a single message sent from a sender is reliably delivered to the intended receiver.
  3. Reliable Delivery (Multiple Messages): Tests that multiple messages sent in sequence are all delivered successfully.
  4. Reliable Delivery (Exceeding Window Size): Tests the handling of message delivery when the number of messages exceeds the window size, ensuring proper acknowledgment processing and window advancement.
  5. No Duplication (Duplicate Packets): Ensures that duplicate packets with the same sequence number are not delivered multiple times.
  6. Authenticity (HMAC Verification): Verifies that messages are authenticated using HMAC, ensuring that tampered messages are rejected.
  7. Authenticity (Sender ID Verification): Tests that messages claiming to be from a different sender are rejected, preventing impersonation attacks.

- `ConsensusTest` - Tests the HotStuff consensus protocol implementation. The following scenarios are covered:
  1. NEW_VIEW Collection: Verifies quorum threshold and ensures the highest Quorum Certificate (QC) is selected as the parent for new proposals.
  2. Vote Accumulation: Tests the full PREPARE -> PRE_COMMIT -> COMMIT -> DECIDE pipeline and ensures extra votes beyond quorum do not cause double transitions.
  3. View Change: Ensures stale votes are rejected after the view advances and that replicas ignore PREPARE messages from a crashed leader.
  4. Safety: Validates that conflicting nodes are pruned from the local tree after a competing node is decided.
  5. Byzantine Behavior: Tests that forged or tampered messages are rejected, including PRE_COMMIT for unknown nodes, DECIDE with forged QC signatures, and replayed messages.
  6. Future Message Buffering: Ensures out-of-order messages are buffered and replayed in protocol order when the view advances, while messages beyond the lookahead window are dropped.

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
- `6` forgedQC - sends a QC with a forged signature.
- `7` spoofSenderId - impersonates another replica by forging the sender ID in messages.
- `8` skipPhase - skips an entire phase and proceeds to the next phase.

To test Byzantine behaviour, change the second argument of the desired replica in `init.sh` to the corresponding flag before running the script.

The system tolerates up to f Byzantine replicas where n >= 3f + 1. With the default four replicas, one Byzantine replica can be tolerated.
