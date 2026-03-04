package pt.tecnico.sec.utils;

import java.io.File;
import java.io.FileReader;
import java.net.InetSocketAddress;
import java.security.PublicKey;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class MemberManager {

    private MemberInfo[] members;

    private final String CONFIG_FILE = "config/membership/memberconfig.json";

    public MemberManager(long ownId) {
        try {
            // Read and parse the JSON config file
            Gson gson = new Gson();
            FileReader reader = new FileReader(new File(CONFIG_FILE));
            JsonObject config = gson.fromJson(reader, JsonObject.class);
            reader.close();

            int numberOfMembers = config.get("numberOfMembers").getAsInt();
            JsonArray serversArray = config.getAsJsonArray("servers");

            members = new MemberInfo[numberOfMembers - 1];
            int index = 0;

            // Populate members array with server information
            for (int i = 0; i < serversArray.size(); i++) {
                JsonObject server = serversArray.get(i).getAsJsonObject();

                // Skip own member info, because we don't want to send messages to ourselves
                long id = server.get("id").getAsLong();
                if (id == ownId) {
                    continue;
                }

                String host = server.get("host").getAsString();
                int port = server.get("port").getAsInt();
                String keyFile = server.get("keyfile").getAsString();

                PublicKey publicSignatureKey = Crypto.getReplicaPublicKey(id);

                InetSocketAddress addr = new InetSocketAddress(host, port);

                members[index++] = new MemberInfo(id, publicSignatureKey, keyFile, addr);
            }
        } catch (Exception e) {
            System.err.println("Error loading member configuration:");
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
