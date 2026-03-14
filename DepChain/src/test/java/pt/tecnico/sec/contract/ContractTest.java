import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.*;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.tracing.StandardJsonTracer;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

public class ContractTest {

        public static void main(String[] args) {
                SimpleWorld simpleWorld = new SimpleWorld();

                Address senderAddress = Address.fromHexString(
                                "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
                simpleWorld.createAccount(senderAddress, 0, Wei.fromEth(100));
                MutableAccount senderAccount = (MutableAccount) simpleWorld.get(
                                senderAddress);
                System.out.println("Sender Account");
                System.out.println("  Address: " + senderAccount.getAddress());
                System.out.println("  Balance: " + senderAccount.getBalance());
                System.out.println("  Nonce: " + senderAccount.getNonce());
                System.out.println();

                Address contractAddress = Address.fromHexString(
                                "1234567891234567891234567891234567891234");
                simpleWorld.createAccount(contractAddress, 0, Wei.fromEth(0));
                MutableAccount contractAccount = (MutableAccount) simpleWorld.get(
                                contractAddress);
                System.out.println("Contract Account");
                System.out.println("  Address: " + contractAccount.getAddress());
                System.out.println("  Balance: " + contractAccount.getBalance());
                System.out.println("  Nonce: " + contractAccount.getNonce());
                System.out.println("  Storage:");
                System.out.println(
                                "    Slot 0: " +
                                                simpleWorld
                                                                .get(contractAddress)
                                                                .getStorageValue(UInt256.valueOf(0)));
                String paddedAddress = padHexStringTo256Bit(
                                senderAddress.toHexString());
                String stateVariableIndex = convertIntegerToHex256Bit(1);
                String storageSlotMapping = Numeric.toHexStringNoPrefix(
                                Hash.sha3(
                                                Numeric.hexStringToByteArray(paddedAddress + stateVariableIndex)));
                System.out.println(
                                "    Slot SHA3[msg.sender||1] (mapping): " +
                                                simpleWorld
                                                                .get(contractAddress)
                                                                .getStorageValue(UInt256
                                                                                .fromHexString(storageSlotMapping)));
                System.out.println();

                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                PrintStream printStream = new PrintStream(byteArrayOutputStream);
                StandardJsonTracer tracer = new StandardJsonTracer(
                                printStream,
                                true,
                                true,
                                true,
                                true);

                var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
                executor.tracer(tracer);
                executor.code(Bytes.fromHexString( // TODO:
                                "608060405234801561000f575f5ffd5b506004361061003f575f3560e01c806382ab890a14610043578063ef48e0bd1461005f578063f1351b931461007d575b5f5ffd5b61005d6004803603810190610058919061017b565b61009b565b005b6100676100f8565b60405161007491906101b5565b60405180910390f35b61008561013c565b60405161009291906101b5565b60405180910390f35b8060015f3373ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020019081526020015f208190555060015f5f8282546100ee91906101fb565b9250508190555050565b5f60015f3373ffffffffffffffffffffffffffffffffffffffff1673ffffffffffffffffffffffffffffffffffffffff1681526020019081526020015f2054905090565b5f5f54905090565b5f5ffd5b5f819050919050565b61015a81610148565b8114610164575f5ffd5b50565b5f8135905061017581610151565b92915050565b5f602082840312156101905761018f610144565b5b5f61019d84828501610167565b91505092915050565b6101af81610148565b82525050565b5f6020820190506101c85f8301846101a6565b92915050565b7f4e487b71000000000000000000000000000000000000000000000000000000005f52601160045260245ffd5b5f61020582610148565b915061021083610148565b9250828201905080821115610228576102276101ce565b5b9291505056fea264697066735822122081be72777a233301c3007a9bee7532cb0f9535e2a979e52266e5180a60d40d9264736f6c634300081f0033"));
                executor.sender(senderAddress);
                executor.receiver(contractAddress);
                executor.worldUpdater(simpleWorld.updater());
                executor.commitWorldState();

                executor.callData(Bytes.fromHexString("f1351b93"));
                executor.execute();
                int count = extractIntegerFromReturnData(byteArrayOutputStream);
                System.out.println(
                                "Output of 'retrieve_count():' " + Integer.toString(count));

                executor.callData(Bytes.fromHexString("ef48e0bd"));
                executor.execute();
                int number = extractIntegerFromReturnData(byteArrayOutputStream);
                System.out.println(
                                "Output of 'retrieve_number():' " + Integer.toString(number));

                executor.callData(
                                Bytes.fromHexString("82ab890a" + convertIntegerToHex256Bit(42))); // update(42)
                executor.execute();

                executor.callData(Bytes.fromHexString("f1351b93"));
                executor.execute();
                count = extractIntegerFromReturnData(byteArrayOutputStream);
                System.out.println(
                                "Output of 'retrieve_count():' " + Integer.toString(count));

                executor.callData(Bytes.fromHexString("ef48e0bd"));
                executor.execute();
                number = extractIntegerFromReturnData(byteArrayOutputStream);
                System.out.println(
                                "Output of 'retrieve_number():' " + Integer.toString(number));

                System.out.println();
                System.out.println("Sender Account");
                System.out.println("  Address: " + senderAccount.getAddress());
                System.out.println("  Balance: " + senderAccount.getBalance());
                System.out.println("  Nonce: " + senderAccount.getNonce());
                System.out.println();

                System.out.println("Contract Account");
                System.out.println("  Address: " + contractAccount.getAddress());
                System.out.println("  Balance: " + contractAccount.getBalance());
                System.out.println("  Nonce: " + contractAccount.getNonce());
                System.out.println("  Storage:");
                System.out.println(
                                "    Slot 0 (count): " +
                                                simpleWorld
                                                                .get(contractAddress)
                                                                .getStorageValue(UInt256.valueOf(0)));
                System.out.println(
                                "    Slot SHA3[msg.sender||1] (mapping): " +
                                                simpleWorld
                                                                .get(contractAddress)
                                                                .getStorageValue(UInt256
                                                                                .fromHexString(storageSlotMapping)));
        }

        public static int extractIntegerFromReturnData(
                        ByteArrayOutputStream byteArrayOutputStream) {
                String[] lines = byteArrayOutputStream.toString().split("\\r?\\n");
                JsonObject jsonObject = JsonParser.parseString(
                                lines[lines.length - 1]).getAsJsonObject();

                String memory = jsonObject.get("memory").getAsString();

                JsonArray stack = jsonObject.get("stack").getAsJsonArray();
                int offset = Integer.decode(stack.get(stack.size() - 1).getAsString());
                int size = Integer.decode(stack.get(stack.size() - 2).getAsString());

                String returnData = memory.substring(
                                2 + offset * 2,
                                2 + offset * 2 + size * 2);
                return Integer.decode("0x" + returnData);
        }

        public static String convertIntegerToHex256Bit(int number) {
                BigInteger bigInt = BigInteger.valueOf(number);

                return String.format("%064x", bigInt);
        }

        public static String padHexStringTo256Bit(String hexString) {
                if (hexString.startsWith("0x")) {
                        hexString = hexString.substring(2);
                }

                int length = hexString.length();
                int targetLength = 64;

                if (length >= targetLength) {
                        return hexString.substring(0, targetLength);
                }

                return "0".repeat(targetLength - length) + hexString;
        }
}
