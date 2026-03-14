package pt.tecnico.sec.account;

public abstract class Account {

    protected final String address; 
    protected long balance; 
    private long nonce;  // expected next transaction nonce

    protected Account(String address, long balance, long nonce) {
        if (address == null || address.isBlank())
            throw new IllegalArgumentException("Address cannot be null or blank");
        if (balance < 0)
            throw new IllegalArgumentException("Balance cannot be negative");

        this.address = address.toLowerCase();
        this.balance = balance;
        this.nonce   = nonce;
    }

    public void deductBalance(long amount) {
        if (amount < 0)
            throw new IllegalArgumentException("Amount cannot be negative");
        if (balance < amount)
            throw new IllegalStateException(
                "Insufficient balance: has " + balance + ", needs " + amount);
        balance -= amount;
    }

    public void addBalance(long amount) {
        if (amount < 0)
            throw new IllegalArgumentException("Amount cannot be negative");
        balance += amount;
    }

    public void incrementNonce() { 
        nonce++; 
    }

    public long getBalance() { 
        return balance; 
    }

    public long getNonce() { 
        return nonce; }

    public String getAddress() { 
        return address; 
    }

    public abstract boolean isContract();
}