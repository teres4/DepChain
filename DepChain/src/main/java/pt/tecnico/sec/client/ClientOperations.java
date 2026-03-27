package pt.tecnico.sec.client;

import java.math.BigInteger;
import java.security.PrivateKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    private long getBalanceNonce = 0; // Separate nonce for read-only balance queries

    public static final String IST_COIN_ADDRESS = "0x86b7efc7c33d883a69cb5d57c7580a699f5d15e6";

    private final Map<Long, ConcurrentLinkedQueue<CommandResponse>> responsesByNonce = new ConcurrentHashMap<>();

    private final Map<Long, String> acceptedResults = new ConcurrentHashMap<>();

    public ClientOperations(int clientId, AuthPerfLink authPerfLink) {
        this.authPerfLink = authPerfLink;
        this.clientId = clientId;

        MemberInfo[] replicas = authPerfLink.getMembers();
        this.numReplicas = replicas.length;
        System.err.println("NUMBER OF REPLICAS " + numReplicas);
        this.f = (this.numReplicas - 1) / 3;
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
        ConcurrentLinkedQueue<CommandResponse> responses = responsesByNonce.computeIfAbsent(
                response.getNonce(), k -> new ConcurrentLinkedQueue<>());
        responses.add(response);
    }

    // waits for f+1 equal successful responses from replicas to ensure
    // at least one honest replica received the command
    private void waitForResponses(long nonce) throws Exception {
        int requiredResponses = this.f + 1; // Need f+1 responses
        ConcurrentLinkedQueue<CommandResponse> responses = responsesByNonce.get(nonce);

        // Wait until f+1 responses share the same result
        while (true) {
            Map<String, Long> resultCounts = responses.stream()
                    .filter(response -> response.getNonce() == nonce && response.isSuccess())
                    .collect(Collectors.groupingBy(CommandResponse::getResult, Collectors.counting()));

            if (resultCounts.values().stream().anyMatch(count -> count >= requiredResponses)) {
                break;
            }
            Thread.sleep(100);
        }

        // Find the result agreed upon by f+1 replicas
        String matchingResult = responses.stream()
                .filter(response -> response.getNonce() == nonce && response.isSuccess())
                .collect(Collectors.groupingBy(CommandResponse::getResult, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() >= requiredResponses)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();

        System.out.println("Command accepted by replicas. ");
        acceptedResults.put(nonce, matchingResult);
    }

    private void waitForExecutionResponses(long nonce) throws Exception {
        int requiredResponses = this.f + 1; // Need f+1 responses

        String acceptedResult = acceptedResults.get(nonce);
        ConcurrentLinkedQueue<CommandResponse> responses = responsesByNonce.get(nonce);

        while (true) {
            Map<String, Long> resultCounts = responses.stream()
                    .filter(response -> response.getNonce() == nonce && response.isSuccess())
                    .filter(response -> !acceptedResult.equals(response.getResult()))
                    .collect(Collectors.groupingBy(CommandResponse::getResult, Collectors.counting()));

            if (resultCounts.values().stream().anyMatch(count -> count >= requiredResponses)) 
                break;

            Thread.sleep(100);
        }

        String matchingResult = responses.stream()
                .filter(response -> response.getNonce() == nonce && response.isSuccess())
                .filter(response -> !acceptedResult.equals(response.getResult()))
                .collect(Collectors.groupingBy(CommandResponse::getResult, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() >= requiredResponses)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();

        System.out.println("Transaction " + nonce + " executed. Result: " + matchingResult);
        responsesByNonce.remove(nonce);
        acceptedResults.remove(nonce);
    }

    private void waitForExecutionResponsesAsync(long nonce) {
        Thread t = new Thread(() -> {
            try {
                waitForExecutionResponses(nonce);
            } catch (Exception e) {
                System.err.println("Execution confirmation wait failed: " + e.getMessage());
            }
        }, "exec-confirm-" + nonce);
        t.setDaemon(true);
        t.start();
    }

    public void transferDepCoin(int recipientId, long amount, long gasPrice, long gasLimit) throws Exception {
        PrivateKey clientPrivKey = Crypto.getClientPrivateKey(this.clientId);

        String senderAddress = Crypto.getClientAddress(this.clientId);
        String recipientAddress = Crypto.getClientAddress(recipientId);

        this.nonce++;

        // build unsigned transaction first to get signable bytes
        Transaction unsignedTx = new Transaction(
                Transaction.Type.DEPCOIN_TRANSFER,
                senderAddress,
                recipientAddress,
                amount,
                new byte[0],
                gasPrice,
                gasLimit,
                this.nonce,
                null);

        // sign the transaction bytes
        String base64Signature = Crypto.signMessage(
                Base64.getEncoder().encodeToString(unsignedTx.toSignableBytes()),
                clientPrivKey);
        byte[] signatureBytes = Base64.getDecoder().decode(base64Signature);

        // build final signed transaction
        Transaction tx = new Transaction(
                Transaction.Type.DEPCOIN_TRANSFER,
                senderAddress,
                recipientAddress,
                amount,
                new byte[0],
                gasPrice,
                gasLimit,
                this.nonce,
                signatureBytes);

        // serialize and send
        String txJson = new Gson().toJson(tx);

        Command command = new Command(CommandType.TRANSFERDEPCOIN, this.clientId, txJson);

        responsesByNonce.put(this.nonce, new ConcurrentLinkedQueue<>());
        acceptedResults.remove(this.nonce);

        logger("Sending DepCoin transfer transaction: " + txJson);

        sendToAllReplicas(command.toJson());
        waitForResponses(this.nonce);
        waitForExecutionResponsesAsync(this.nonce);
    }

    public void transferISTCoin(int recipientId, long amount, long gasPrice, long gasLimit) throws Exception {
        PrivateKey clientPrivKey = Crypto.getClientPrivateKey(this.clientId);

        String senderAddress = Crypto.getClientAddress(this.clientId);
        String recipientAddress = Crypto.getClientAddress(recipientId);
        String istCoinContractAddress = IST_COIN_ADDRESS;

        this.nonce++;

        // ABI encode transfer(recipientAddress, amount) — this becomes the calldata
        // the EVM uses the function selector to route to transfer() and decodes the
        // arguments
        Function function = new Function(
                "transfer",
                Arrays.asList(
                        new Address(recipientAddress),
                        new Uint256(BigInteger.valueOf(amount))),
                Collections.emptyList());
        byte[] input = Numeric.hexStringToByteArray(FunctionEncoder.encode(function));

        // build unsigned transaction
        Transaction unsignedTx = new Transaction(
                Transaction.Type.CONTRACT_CALL,
                senderAddress,
                istCoinContractAddress,
                0,
                input,
                gasPrice,
                gasLimit,
                this.nonce,
                null);

        // sign the transaction bytes
        String base64Signature = Crypto.signMessage(
                Base64.getEncoder().encodeToString(unsignedTx.toSignableBytes()),
                clientPrivKey);
        byte[] signatureBytes = Base64.getDecoder().decode(base64Signature);

        // build final signed transaction
        Transaction tx = new Transaction(
                Transaction.Type.CONTRACT_CALL,
                senderAddress,
                istCoinContractAddress,
                0,
                input,
                gasPrice,
                gasLimit,
                this.nonce,
                signatureBytes);

        String txJson = new Gson().toJson(tx);

        Command command = new Command(CommandType.TRANSFERISTCOIN, this.clientId, txJson);

        responsesByNonce.put(this.nonce, new ConcurrentLinkedQueue<>());
        acceptedResults.remove(this.nonce);

        sendToAllReplicas(command.toJson());
        waitForResponses(this.nonce);
        waitForExecutionResponsesAsync(this.nonce);
    }

    public void approveISTCoin(int spenderId, long expectedCurrentAllowance, long newAllowance, long gasPrice, long gasLimit) throws Exception {
        PrivateKey clientPrivKey = Crypto.getClientPrivateKey(this.clientId);
        String senderAddress = Crypto.getClientAddress(this.clientId);
        String spenderAddress = Crypto.getClientAddress(spenderId);
        String istCoinContractAddress = IST_COIN_ADDRESS;

        this.nonce++;

        Function function = new Function(
                "safeApprove",
                Arrays.asList(
                        new Address(spenderAddress),
                        new Uint256(BigInteger.valueOf(expectedCurrentAllowance)),
                        new Uint256(BigInteger.valueOf(newAllowance))),
                Collections.emptyList());
        byte[] input = Numeric.hexStringToByteArray(FunctionEncoder.encode(function));

        Transaction unsignedTx = new Transaction(
                Transaction.Type.CONTRACT_CALL,
                senderAddress, istCoinContractAddress, 0, input,
                gasPrice, gasLimit, this.nonce, null);

        String base64Signature = Crypto.signMessage(
                Base64.getEncoder().encodeToString(unsignedTx.toSignableBytes()),
                clientPrivKey);
        byte[] signatureBytes = Base64.getDecoder().decode(base64Signature);

        Transaction tx = new Transaction(
                Transaction.Type.CONTRACT_CALL,
                senderAddress, istCoinContractAddress, 0, input,
                gasPrice, gasLimit, this.nonce, signatureBytes);

        String txJson = new Gson().toJson(tx);
        Command command = new Command(CommandType.APPROVEISTCOIN, this.clientId, txJson);

        responsesByNonce.put(this.nonce, new ConcurrentLinkedQueue<>());
        acceptedResults.remove(this.nonce);
        sendToAllReplicas(command.toJson());
        waitForResponses(this.nonce);
    }

    public void transferFromISTCoin(int ownerId, int recipientId, long amount, long gasPrice, long gasLimit) throws Exception {
        PrivateKey clientPrivKey = Crypto.getClientPrivateKey(this.clientId);
        String senderAddress = Crypto.getClientAddress(this.clientId);  // the spender (msg.sender)
        String ownerAddress = Crypto.getClientAddress(ownerId);
        String recipientAddress = Crypto.getClientAddress(recipientId);
        String istCoinContractAddress = IST_COIN_ADDRESS;

        this.nonce++;

        Function function = new Function(
                "transferFrom",
                Arrays.asList(
                        new Address(ownerAddress),
                        new Address(recipientAddress),
                        new Uint256(BigInteger.valueOf(amount))),
                Collections.emptyList());
        byte[] input = Numeric.hexStringToByteArray(FunctionEncoder.encode(function));

        Transaction unsignedTx = new Transaction(
                Transaction.Type.CONTRACT_CALL,
                senderAddress, istCoinContractAddress, 0, input,
                gasPrice, gasLimit, this.nonce, null);

        String base64Signature = Crypto.signMessage(
                Base64.getEncoder().encodeToString(unsignedTx.toSignableBytes()),
                clientPrivKey);
        byte[] signatureBytes = Base64.getDecoder().decode(base64Signature);

        Transaction tx = new Transaction(
                Transaction.Type.CONTRACT_CALL,
                senderAddress, istCoinContractAddress, 0, input,
                gasPrice, gasLimit, this.nonce, signatureBytes);

        String txJson = new Gson().toJson(tx);
        Command command = new Command(CommandType.TRANSFERFROMISTCOIN, this.clientId, txJson);

        responsesByNonce.put(this.nonce, new ConcurrentLinkedQueue<>());
        acceptedResults.remove(this.nonce);
        sendToAllReplicas(command.toJson());
        waitForResponses(this.nonce);
    }

    private void logger(String message) {
        // print in cyan color
        System.out.println("\u001B[36m" + message + "\u001B[0m");
    }

    public void getDepCoinBalance() throws Exception {
        PrivateKey clientPrivKey = Crypto.getClientPrivateKey(this.clientId);
        String senderAddress = Crypto.getClientAddress(this.clientId);

                this.getBalanceNonce++;
                long currentQueryNonce = this.getBalanceNonce;

        // A GET_BALANCE tx: no recipient, no amount, no input — just signed identity + nonce
        Transaction unsignedTx = new Transaction(
                Transaction.Type.GET_BALANCE,
                senderAddress,
                null,      // no recipient
                0,         // no amount
                currentQueryNonce,
                null);     // unsigned for now

        String base64Signature = Crypto.signMessage(
                Base64.getEncoder().encodeToString(unsignedTx.toSignableBytes()),
                clientPrivKey);
        byte[] signatureBytes = Base64.getDecoder().decode(base64Signature);

        Transaction tx = new Transaction(
                Transaction.Type.GET_BALANCE,
                senderAddress,
                null,
                0,
                currentQueryNonce,
                signatureBytes);

        String txJson = new Gson().toJson(tx);
        Command command = new Command(CommandType.GETDEPCOINBALANCE, this.clientId, txJson);

        responsesByNonce.put(currentQueryNonce, new ConcurrentLinkedQueue<>());
        acceptedResults.remove(currentQueryNonce);

        logger("Sending DepCoin balance query");

        sendToAllReplicas(command.toJson());
        // balance queries are read-only: just wait for f+1 matching results, no execution phase
        waitForResponses(currentQueryNonce);
        String balance = acceptedResults.get(currentQueryNonce);
        System.out.println("DepCoin balance: " + balance);
        responsesByNonce.remove(currentQueryNonce);
        acceptedResults.remove(currentQueryNonce);
    }

    public void getISTCoinBalance() throws Exception {
        PrivateKey clientPrivKey = Crypto.getClientPrivateKey(this.clientId);
        String senderAddress = Crypto.getClientAddress(this.clientId);

        this.getBalanceNonce++;
        long currentQueryNonce = this.getBalanceNonce;

        // ABI-encode balanceOf(senderAddress) — the ERC-20 view function
        Function function = new Function(
                "balanceOf",
                Arrays.asList(new Address(senderAddress)),
                Collections.emptyList());
        byte[] input = Numeric.hexStringToByteArray(FunctionEncoder.encode(function));

        Transaction unsignedTx = new Transaction(
                Transaction.Type.GET_BALANCE,
                senderAddress,
                IST_COIN_ADDRESS,
                0,
                input,
                0, 
                0, 
                currentQueryNonce,
                null);

        String base64Signature = Crypto.signMessage(
                Base64.getEncoder().encodeToString(unsignedTx.toSignableBytes()),
                clientPrivKey);
        byte[] signatureBytes = Base64.getDecoder().decode(base64Signature);

        Transaction tx = new Transaction(
                Transaction.Type.GET_BALANCE,
                senderAddress,
                IST_COIN_ADDRESS,
                0,
                input,
                0,
                0,
                currentQueryNonce,
                signatureBytes);

        String txJson = new Gson().toJson(tx);
        Command command = new Command(CommandType.GETISTCOINBALANCE, this.clientId, txJson);

        responsesByNonce.put(currentQueryNonce, new ConcurrentLinkedQueue<>());
        acceptedResults.remove(currentQueryNonce);

        logger("Sending IST Coin balance query");

        sendToAllReplicas(command.toJson());
                waitForResponses(currentQueryNonce);
                String balance = acceptedResults.get(currentQueryNonce);
        System.out.println("IST Coin balance: " + balance);
                responsesByNonce.remove(currentQueryNonce);
                acceptedResults.remove(currentQueryNonce);
    }
}