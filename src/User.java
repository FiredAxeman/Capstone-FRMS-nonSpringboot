public class User {
    protected String username;
    protected String passwordHash;
    protected String role;

    public boolean authenticate(String inputHash) {
        return this.passwordHash.equals(inputHash);
    }
    public void authorize() { /* Authorization logic */ }
}
