package pt.tecnico.sec.utils;

import java.io.File;
import java.io.FileReader;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class MemberManagement {

    private MemberInfo[] members;

    private final String CONFIG_FILE = "config/membership/memberconfig.json";
    private final String ADRESS = "localhost";

    public MemberManagement() {
        try {
            // Read and parse the JSON config file
            Gson gson = new Gson();
            FileReader reader = new FileReader(new File(CONFIG_FILE));
            JsonObject config = gson.fromJson(reader, JsonObject.class);
            reader.close();

            int numberOfMembers = config.get("numberOfMembers").getAsInt();
            JsonArray serversArray = config.getAsJsonArray("servers");

            members = new MemberInfo[numberOfMembers];

            // Populate members array with server information
            for (int i = 0; i < serversArray.size(); i++) {
                JsonObject server = serversArray.get(i).getAsJsonObject();

                long id = server.get("id").getAsLong();
                int port = server.get("port").getAsInt();
                String keyFile = server.get("keyfile").getAsString();

                // Load public key from file
                PublicKey publicKey = Crypto.getPublicKey("config/keys/" + keyFile);

                // Create InetSocketAddress (using localhost as default)
                InetSocketAddress addr = new InetSocketAddress(ADRESS, port);

                // Create MemberInfo object
                members[i] = new MemberInfo(id, publicKey, keyFile, addr);
            }
        } catch (Exception e) {
            System.err.println("Error loading member configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public MemberInfo[] getMembers() {
        return members;
    }

    public MemberInfo getMember(long id) {
        for (MemberInfo member : members) {
            if (member.getId() == id) {
                return member;
            }
        }
        return null;
    }

    public MemberInfo getMemberByAddress(InetSocketAddress addr) {
        for (MemberInfo member : members) {
            if (member.getAddr().equals(addr)) {
                return member;
            }
        }
        return null;
    }
}
