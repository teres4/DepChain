package pt.tecnico.sec.blockchain;

public class AccountState {
    private long depCoinBalance;
    private long nonce;  // expected next transaction nonce

    public AccountState(long depCoinBalance, long nonce) {
        this.depCoinBalance = depCoinBalance;
        this.nonce = nonce;
    }

    public void deductFee(long fee) {
        if (fee > depCoinBalance)
            throw new IllegalStateException("Insufficient DepCoin balance for gas fee");
        depCoinBalance -= fee;
    }

    public void addBalance(long amount) { depCoinBalance += amount; }
    public void deductBalance(long amount) {
        if (amount > depCoinBalance)
            throw new IllegalStateException("Insufficient DepCoin balance");
        depCoinBalance -= amount;
    }

    public void incrementNonce() { nonce++; }

    public long getDepCoinBalance() { return depCoinBalance; }
    public long getNonce() { return nonce; }
}