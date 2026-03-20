package pt.tecnico.sec.client;

import java.math.BigInteger;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.utils.Numeric;

import com.google.gson.Gson;

import pt.tecnico.sec.apl.AuthPerfLink;
import pt.tecnico.sec.blockchain.Transaction;
import pt.tecnico.sec.client.message.Command;
import pt.tecnico.sec.client.message.CommandResponse;
import pt.tecnico.sec.client.message.CommandType;
import pt.tecnico.sec.utils.Crypto;
import pt.tecnico.sec.utils.MemberInfo;

public class ClientOperations {
    private final AuthPerfLink authPerfLink;
    private final int clientId;
    private final int numReplicas;
    private final int f; // Fault tolerance parameter
    private long nonce = 0; // To prevent replay attacks

    public static final String IST_COIN_ADDRESS = "0xdddddddddddddddddddddddddddddddddddddddd";

    private final ConcurrentLinkedQueue<CommandResponse> currentResponses = new ConcurrentLinkedQueue<>();
    private long currentNonce = -1;

    public ClientOperations(int clientId, AuthPerfLink authPerfLink) {
        this.authPerfLink = authPerfLink;
        this.clientId = clientId;

        MemberInfo[] replicas = authPerfLink.getMembers();
        this.numReplicas = replicas.length;
        System.err.println("NUMBER OF REPLICAS " + numReplicas);
        this.f = (this.numReplicas - 1) / 3;
    }

    public void append(String stringToAppend) throws Exception {
        PrivateKey clientPrivKey = Crypto.getClientPrivateKey(this.clientId);

        this.nonce++;
        String signableContent = CommandType.APPEND + ":" + this.clientId + ":" + stringToAppend + ":" + this.nonce;
        String signature = Crypto.signMessage(signableContent, clientPrivKey);

        Command appendCommand = new Command(CommandType.APPEND, this.clientId, stringToAppend, signature, this.nonce);
        String commandJson = appendCommand.toJson();

        // Clear previous responses and set current nonce
        currentResponses.clear();
        currentNonce = this.nonce;

        sendToAllReplicas(commandJson);
        waitForResponses(this.nonce);
    }

    private void sendToAllReplicas(String messageJson) {
        MemberInfo[] replicas = authPerfLink.getMembers();
        for (MemberInfo replica : replicas) {
            try {
                authPerfLink.sendData(replica.getId(), messageJson.getBytes());
            } catch (Exception e) {
                System.err.println("Failed to send to replica " + replica.getId() + ": " + e.getMessage());
            }
        }
    }

    public void handleCommandResponse(CommandResponse response) {
        // Only accept responses for the current command
        if (response.getNonce() == currentNonce) {
            currentResponses.add(response);
        }
    }

    // waits for f+1 equal successful responses from replicas to ensure
    // at least one honest replica executed the command
    private void waitForResponses(long nonce) throws Exception {
        int requiredResponses = this.f + 1; // Need f+1 responses

        // Wait until f+1 responses share the same result
        while (true) {
            Map<String, Long> resultCounts = currentResponses.stream()
                    .filter(response -> response.getNonce() == nonce && response.isSuccess())
                    .collect(Collectors.groupingBy(CommandResponse::getResult, Collectors.counting()));

            if (resultCounts.values().stream().anyMatch(count -> count >= requiredResponses)) {
                break;
            }
            Thread.sleep(100);
        }

        // Find the result agreed upon by f+1 replicas
        String matchingResult = currentResponses.stream()
                .filter(response -> response.getNonce() == nonce && response.isSuccess())
                .collect(Collectors.groupingBy(CommandResponse::getResult, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() >= requiredResponses)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();

        System.out.println("Command executed successfully. Result: " + matchingResult);
    }

    public void transferDepCoin(int recipientId, long amount, long gasPrice, long gasLimit) throws Exception {
        PrivateKey clientPrivKey = Crypto.getClientPrivateKey(this.clientId);

        String senderAddress = Crypto.getClientAddress(this.clientId);
        String recipientAddress = Crypto.getClientAddress(recipientId);

        this.nonce++;

        // build unsigned transaction first to get signable bytes
        Transaction unsignedTx = new Transaction(
            senderAddress,
            recipientAddress,
            amount,
            new byte[0],
            gasPrice,
            gasLimit,
            this.nonce,
            null
        );

        // sign the transaction bytes
        String base64Signature = Crypto.signMessage(
            Base64.getEncoder().encodeToString(unsignedTx.toSignableBytes()),
            clientPrivKey
        );
        byte[] signatureBytes = Base64.getDecoder().decode(base64Signature);

        // build final signed transaction
        Transaction tx = new Transaction(
            senderAddress,
            recipientAddress,
            amount,
            new byte[0],
            gasPrice,
            gasLimit,
            this.nonce,
            signatureBytes
        );

        // serialize and send
        String txJson = new Gson().toJson(tx);

        // TODO: will the client be able to send multiple transactions?
        // or do we wait for the response like in stage 1?
        currentResponses.clear();
        currentNonce = this.nonce;

        sendToAllReplicas(txJson);
        waitForResponses(this.nonce);
    }

    public void transferISTCoin(int recipientId, long amount, long gasPrice, long gasLimit) throws Exception {
        PrivateKey clientPrivKey = Crypto.getClientPrivateKey(this.clientId);

        String senderAddress = Crypto.getClientAddress(this.clientId);
        String recipientAddress = Crypto.getClientAddress(recipientId);
        String istCoinContractAddress = IST_COIN_ADDRESS;

        this.nonce++;

        // ABI encode transfer(recipientAddress, amount) — this becomes the calldata
        // the EVM uses the function selector to route to transfer() and decodes the arguments
        Function function = new Function(
            "transfer",
            Arrays.asList(
                new Address(recipientAddress),
                new Uint256(BigInteger.valueOf(amount))
            ),
            Collections.emptyList()
        );
        byte[] input = Numeric.hexStringToByteArray(FunctionEncoder.encode(function));

        // build unsigned transaction
        Transaction unsignedTx = new Transaction(
            senderAddress,
            istCoinContractAddress,
            0,
            input,
            gasPrice,
            gasLimit,
            this.nonce,
            null
        );

        // sign the transaction bytes
        String base64Signature = Crypto.signMessage(
            Base64.getEncoder().encodeToString(unsignedTx.toSignableBytes()),
            clientPrivKey
        );
        byte[] signatureBytes = Base64.getDecoder().decode(base64Signature);

        // build final signed transaction
        Transaction tx = new Transaction(
            senderAddress,
            istCoinContractAddress,
            0,
            input,
            gasPrice,
            gasLimit,
            this.nonce,
            signatureBytes
        );

        String txJson = new Gson().toJson(tx);

        // TODO: will the client be able to send multiple transactions?
        // or do we wait for the response like in stage 1?
        currentResponses.clear();
        currentNonce = this.nonce;

        sendToAllReplicas(txJson);
        waitForResponses(this.nonce);
    }

    // private final String from;
    // private final String to; // null for contract deployment
    // private final long amount; // DepCoin amount, 0 for contract calls

    // // contract call or deployment bytecode, empty for plain DepCoin transfers
    // private final byte[] input;

    // // price per gas unit
    // private final long gasPrice;

    // // max gas units willing to spend
    // private final long gasLimit;

}