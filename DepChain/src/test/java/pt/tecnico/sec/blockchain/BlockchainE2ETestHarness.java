package pt.tecnico.sec.blockchain;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.gson.Gson;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.client.message.CommandResponse;
import pt.tecnico.sec.client.message.CommandType;
import pt.tecnico.sec.replica.Replica;
import pt.tecnico.sec.utils.Crypto;

final class BlockchainE2ETestHarness {
    private final Replica replica;
    private final Blockchain blockchain;
    private final CopyOnWriteArrayList<CapturedResponse> responses = new CopyOnWriteArrayList<>();

    BlockchainE2ETestHarness(long replicaId) {
        AuthPerfLink apl = mock(AuthPerfLink.class);
        doAnswer(invocation -> {
            long receiver = invocation.getArgument(0);
            byte[] payload = invocation.getArgument(1);
            String json = new String(payload, StandardCharsets.UTF_8);
            try {
                CommandResponse response = CommandResponse.fromJson(json);
                responses.add(new CapturedResponse(receiver, response));
            } catch (Exception ignored) {
                // Ignore non-command-response payloads.
            }
            return null;
        }).when(apl).sendData(anyLong(), any(byte[].class));

        this.blockchain = new Blockchain(replicaId);
        this.replica = new Replica(replicaId, apl, blockchain);
    }

    Replica replica() {
        return replica;
    }

    Blockchain blockchain() {
        return blockchain;
    }

    long currentNonceFor(long clientId) throws Exception {
        String address = Crypto.getClientAddress(clientId);
        return blockchain.getAccount(address).getNonce();
    }

    Command signedTransfer(long claimedSenderId,
            long signerId,
            long recipientId,
            long amount,
            long gasPrice,
            long gasLimit,
            long nonce) throws Exception {
        String from = Crypto.getClientAddress(claimedSenderId);
        String to = Crypto.getClientAddress(recipientId);

        Transaction unsigned = new Transaction(from, to, amount, new byte[0], gasPrice, gasLimit, nonce, null);
        PrivateKey signerPriv = Crypto.getClientPrivateKey(signerId);
        String base64Signature = Crypto.signMessage(
                Base64.getEncoder().encodeToString(unsigned.toSignableBytes()),
                signerPriv);

        Transaction signed = new Transaction(
                from,
                to,
                amount,
                new byte[0],
                gasPrice,
                gasLimit,
                nonce,
                Base64.getDecoder().decode(base64Signature));

        return new Command(CommandType.TRANSFERDEPCOIN, claimedSenderId, new Gson().toJson(signed));
    }

    List<CommandResponse> awaitResponsesFor(long clientId, long nonce, int atLeast, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<CommandResponse> matched = responses.stream()
                    .filter(r -> r.receiverId == clientId)
                    .map(r -> r.response)
                    .filter(r -> r.getNonce() == nonce)
                    .toList();
            if (matched.size() >= atLeast) {
                return matched;
            }
            Thread.sleep(20);
        }
        return responses.stream()
                .filter(r -> r.receiverId == clientId)
                .map(r -> r.response)
                .filter(r -> r.getNonce() == nonce)
                .toList();
    }

    List<CommandResponse> allResponsesFor(long clientId, long nonce) {
        return responses.stream()
                .filter(r -> r.receiverId == clientId)
                .map(r -> r.response)
                .filter(r -> r.getNonce() == nonce)
                .toList();
    }

    List<CommandResponse> awaitNewResponsesFor(long clientId,
            long nonce,
            int previousCount,
            int atLeastNew,
            long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<CommandResponse> matched = allResponsesFor(clientId, nonce);
            if (matched.size() - previousCount >= atLeastNew) {
                return matched.subList(previousCount, matched.size());
            }
            Thread.sleep(20);
        }
        List<CommandResponse> matched = allResponsesFor(clientId, nonce);
        if (matched.size() <= previousCount) {
            return List.of();
        }
        return matched.subList(previousCount, matched.size());
    }

    private record CapturedResponse(long receiverId, CommandResponse response) {
    }
}
