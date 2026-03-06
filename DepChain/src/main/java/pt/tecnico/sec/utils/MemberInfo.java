package pt.tecnico.sec.utils;

import java.net.InetSocketAddress;
import java.security.PublicKey;

public class MemberInfo {
    private final long id;
    private final PublicKey publicSignatureKey;
    private final String keyFile;
    private final InetSocketAddress addr;

    public MemberInfo(long id, PublicKey publicSignatureKey, String keyFile,
            InetSocketAddress addr) {
        this.id = id;
        this.publicSignatureKey = publicSignatureKey;
        this.keyFile = keyFile;
        this.addr = addr;
    }

    public long getId() {
        return id;
    }

    public PublicKey getPublicSignatureKey() {
        return publicSignatureKey;
    }

    public String getKeyFile() {
        return keyFile;
    }

    public InetSocketAddress getAddr() {
        return addr;
    }

    @Override
    public String toString() {
        return "MemberInfo{" +
                "id=" + id +
                ", publicSignatureKey=" + publicSignatureKey +
                ", keyFile='" + keyFile + '\'' +
                ", addr=" + addr +
                '}';
    }
}