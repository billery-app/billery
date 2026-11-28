package de.billery.saas.api;

import de.billery.api.error.MessageCode;

/**
 * The messages of the SaaS API with their codes (#15); like {@link de.billery.api.error.ErrorCode}
 * for the core, checked by {@code errorCodes.spec.ts} against the SPA's message files.
 */
public enum SaasErrorCode implements MessageCode {
  EMAIL_NOT_VERIFIED(
      "saas.emailNotVerified", "Für die erste Anmeldung ist eine bestätigte E-Mail-Adresse nötig."),
  HEAVY_REQUESTS_RUNNING(
      "saas.heavyRequestsRunning",
      "Es werden gerade schon Dokumente erzeugt. Bitte warten, bis sie fertig sind."),
  INVITATION_NOT_FOUND("saas.invitationNotFound", "Diese Einladung gibt es nicht."),
  INVITE_ADDRESS_INVALID("saas.inviteAddressInvalid", "Bitte eine gültige E-Mail-Adresse angeben."),
  INVITE_ALREADY_INVITED("saas.inviteAlreadyInvited", "Diese Adresse ist bereits eingeladen."),
  INVITE_ALREADY_MEMBER("saas.inviteAlreadyMember", "Diese Person ist bereits Mitglied."),
  INVITE_MAIL_FAILED("saas.inviteMailFailed", "Die E-Mail konnte nicht verschickt werden."),
  INVITE_MAIL_LIMIT("saas.inviteMailLimit", "Das Tageslimit für Einladungsmails ist erreicht."),
  INVITE_MAIL_NOT_SET_UP("saas.inviteMailNotSetUp", "Der Mailversand ist nicht eingerichtet."),
  LAST_OWNER_DEMOTE("saas.lastOwnerDemote", "Der letzte Inhaber kann seine Rolle nicht abgeben."),
  LAST_OWNER_REMOVE("saas.lastOwnerRemove", "Der letzte Inhaber kann nicht entfernt werden."),
  MEMBER_NOT_FOUND("saas.memberNotFound", "Dieses Mitglied gibt es nicht."),
  NO_ROLE("saas.noRole", "Sie haben in dieser Firma keine Rolle."),
  NOT_MEMBER("saas.notMember", "Sie sind nicht Mitglied dieser Firma."),
  NOT_SIGNED_IN("saas.notSignedIn", "Nicht angemeldet."),
  ROLE_NOT_ALLOWED("saas.roleNotAllowed", "Ihre Rolle darf das nicht."),
  ROLE_NOT_MANAGEABLE("saas.roleNotManageable", "Ihre Rolle darf die Rolle {role} nicht vergeben."),
  ROLE_READ_ONLY("saas.roleReadOnly", "Ihre Rolle darf in dieser Firma nur lesen."),
  ROLE_UNKNOWN("saas.roleUnknown", "Unbekannte Rolle: {role}"),
  SIGNUP_LIMIT_IP(
      "saas.signupLimitIp",
      "Von diesem Anschluss wurden heute schon zu viele Firmen angelegt. Bitte versuchen Sie es später wieder."),
  SIGNUP_LIMIT_SYSTEM(
      "saas.signupLimitSystem",
      "Heute können keine neuen Firmen mehr angelegt werden. Bitte versuchen Sie es morgen wieder."),
  SIGNUP_LIMIT_USER(
      "saas.signupLimitUser",
      "Sie haben heute schon mehrere Firmen angelegt. Bitte versuchen Sie es morgen wieder."),
  TENANT_CLOSED("saas.tenantClosed", "Die Firma ist geschlossen oder gesperrt."),
  TENANT_HEADER_INVALID(
      "saas.tenantHeaderInvalid", "Der Header X-Tenant-Id muss die Nummer einer Firma sein."),
  TENANT_HEADER_MISSING(
      "saas.tenantHeaderMissing", "Bitte die Firma im Header X-Tenant-Id angeben."),
  TENANT_NAME_LENGTH("saas.tenantNameLength", "Der Name muss 1 bis 255 Zeichen haben."),
  TENANT_NOT_ACTIVE("saas.tenantNotActive", "Die Firma ist nicht aktiv."),
  TENANT_READ_ONLY(
      "saas.tenantReadOnly", "Die Firma ist geschlossen oder gesperrt und nur noch lesbar."),
  TOKEN_INCOMPLETE(
      "saas.tokenIncomplete", "Die Anmeldung ist unvollständig (Aussteller oder Benutzer fehlt)."),
  TOO_MANY_COMPANIES(
      "saas.tooManyCompanies",
      "Sie besitzen bereits {count} Firmen, mehr sind nicht möglich. Schließen Sie eine Firma oder wenden Sie sich an den Betreiber."),
  TOO_MANY_REQUESTS(
      "saas.tooManyRequests", "Zu viele Anfragen in kurzer Zeit. Bitte einen Moment warten."),
  USER_NOT_CREATED("saas.userNotCreated", "Das Benutzerkonto konnte nicht angelegt werden.");

  private final String key;
  private final String text;

  SaasErrorCode(String key, String text) {
    this.key = key;
    this.text = text;
  }

  @Override
  public String code() {
    return key;
  }

  @Override
  public String template() {
    return text;
  }
}
