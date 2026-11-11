package de.billery.saas.identity;

/**
 * The role of a user in one tenant. Stored in {@code tenant_member.role}; never taken from an
 * identity provider's claims.
 */
public enum Role {
  /** Owns the tenant: everything, including members and deletion. */
  OWNER,
  /** Manages members and settings and works with all documents. */
  ADMIN,
  /** Works with the documents (a tax advisor's access). */
  ACCOUNTANT,
  /** Reads only. */
  READER;

  /**
   * Tells whether this role may use an HTTP method on the tenant's data: a reader only reads (GET,
   * HEAD, OPTIONS), every other role may also write.
   *
   * @param httpMethod the request method
   * @return true if the request is allowed for this role
   */
  public boolean allows(String httpMethod) {
    if (this != READER) {
      return true;
    }
    return "GET".equals(httpMethod) || "HEAD".equals(httpMethod) || "OPTIONS".equals(httpMethod);
  }
}
