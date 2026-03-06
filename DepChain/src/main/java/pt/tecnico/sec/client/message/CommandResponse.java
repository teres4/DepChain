package pt.tecnico.sec.client.message;

import com.google.gson.Gson;

public class CommandResponse {
    private final long clientId;
    private final long nonce;
    private final boolean success;
    private final String result;

    public CommandResponse(long clientId, long nonce, boolean success, String result) {
        this.clientId = clientId;
        this.nonce = nonce;
        this.success = success;
        this.result = result;
    }

    public long getClientId() {
        return clientId;
    }

    public long getNonce() {
        return nonce;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getResult() {
        return result;
    }

    public String toJson() {
        return new Gson().toJson(this);
    }

    public static CommandResponse fromJson(String json) {
        return new Gson().fromJson(json, CommandResponse.class);
    }
}
