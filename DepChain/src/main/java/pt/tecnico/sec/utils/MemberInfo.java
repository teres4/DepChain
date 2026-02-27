package pt.tecnico.sec.utils;

import java.net.InetSocketAddress;
import java.security.PublicKey;

public class MemberInfo {
    private final long id;
    private final PublicKey publicKey;
    private final String keyFile;
    private final InetSocketAddress addr;

    public MemberInfo(long id, PublicKey publicKey, String keyFile, InetSocketAddress addr) {
        this.id = id;
        this.publicKey = publicKey;
        this.keyFile = keyFile;
        this.addr = addr;
    }

    public long getId() {
        return id;
    }

    public PublicKey getPublicKey() {
        return publicKey;
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
                ", keyFile='" + keyFile + '\'' +
                ", addr=" + addr +
                '}';
    }
}