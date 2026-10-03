/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.plugins.icebergcatalog.store;

import com.dremio.common.exceptions.UserException;
import com.google.common.annotations.VisibleForTesting;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NotAuthorizedException;

/**
 * Maps exceptions raised by an Iceberg REST catalog client into {@link UserException}s with a clear
 * message and a hint.
 *
 * <p>The mapping is generic: it only depends on the Iceberg exception types (which the REST client
 * derives from the HTTP status) and on the server message. Catalog specific knowledge (for example
 * Apache Polaris configuration names) only appears in hint text.
 *
 * <p>Exceptions that callers already translate into Dremio catalog exceptions (for example {@link
 * NoSuchNamespaceException} for folders, or {@code CommitFailedException} which the Iceberg commit
 * path turns into a concurrent modification error) are deliberately not handled here.
 */
final class RestCatalogExceptionMapper {

  static final String DROP_WITH_PURGE_HINT =
      "The catalog refused to drop the entity because dropping requires a purge that the catalog"
          + " does not allow. Ask the catalog administrator to allow drop with purge (for Apache"
          + " Polaris: set the catalog property 'polaris.config.drop-with-purge.enabled' to 'true').";

  static final String MISSING_PRIVILEGE_HINT =
      "Check the privileges granted to the principal configured in the source credentials (for"
          + " Apache Polaris: the grants of the catalog roles assigned to the principal role used in"
          + " the 'scope' property).";

  static final String NOT_AUTHORIZED_HINT =
      "Check the 'credential' (or 'token') catalog credential and the 'oauth2-server-uri' catalog"
          + " property. The source state check replaces a client whose session the catalog no"
          + " longer accepts (for example after the catalog restarted), so retry in a minute.";

  private static final Pattern HTML_TAG = Pattern.compile("<[^<>]{1,200}>");

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  /** Maximum length of the server-provided part of an error message. */
  private static final int MAX_DETAIL_LENGTH = 300;

  private RestCatalogExceptionMapper() {}

  /**
   * Same as {@link #forbidden(ForbiddenException, String, Object, UnaryOperator)}, no redaction.
   */
  @VisibleForTesting
  static UserException forbidden(ForbiddenException e, String action, Object entity) {
    return forbidden(e, action, entity, UnaryOperator.identity());
  }

  /**
   * Maps an HTTP 403 from the REST catalog to a permission error.
   *
   * @param e the exception thrown by the REST client
   * @param action what Dremio tried to do, e.g. "drop view"
   * @param entity the entity, e.g. "[ns1.v1]"
   * @param redactor masks the source's secret values in server-provided text: the server message is
   *     copied into the error, the job profile and the logs
   */
  static UserException forbidden(
      ForbiddenException e, String action, Object entity, UnaryOperator<String> redactor) {
    String serverMessage = redactedServerMessage(e, redactor);
    String hint = isPurgeRelated(serverMessage) ? DROP_WITH_PURGE_HINT : MISSING_PRIVILEGE_HINT;
    return UserException.permissionError(redactedCause(e, redactor))
        .message(
            "The Iceberg REST catalog denied the request to %s %s: %s %s",
            action, entity, asSentence(serverMessage), hint)
        .addContext("Action", action + " " + entity)
        .buildSilently();
  }

  /**
   * Same as {@link #notAuthorized(NotAuthorizedException, String, Object, UnaryOperator)}, no
   * redaction.
   */
  @VisibleForTesting
  static UserException notAuthorized(NotAuthorizedException e, String action, Object entity) {
    return notAuthorized(e, action, entity, UnaryOperator.identity());
  }

  /**
   * Maps an HTTP 401 from the REST catalog during an operation (the session token was rejected) to
   * a permission error. See {@link #forbidden(ForbiddenException, String, Object, UnaryOperator)}
   * for the parameters.
   */
  static UserException notAuthorized(
      NotAuthorizedException e, String action, Object entity, UnaryOperator<String> redactor) {
    return UserException.permissionError(redactedCause(e, redactor))
        .message(
            "The Iceberg REST catalog rejected the credentials of the request to %s %s: %s %s",
            action, entity, asSentence(redactedServerMessage(e, redactor)), NOT_AUTHORIZED_HINT)
        .addContext("Action", action + " " + entity)
        .buildSilently();
  }

  /**
   * The server-provided message of {@code e}, redacted and abbreviated to one line. Redacted before
   * abbreviating (so that a cut cannot leave part of a secret behind) and after.
   */
  static String redactedServerMessage(RuntimeException e, UnaryOperator<String> redactor) {
    return redactor.apply(abbreviateDetail(redactor.apply(serverMessage(e))));
  }

  /**
   * Makes a server-provided error text fit for a one-line message: drops HTML markup (e.g. the
   * error page of a proxy), collapses whitespace and cuts it to {@link #MAX_DETAIL_LENGTH}
   * characters. Callers redact secrets before (so that a cut cannot leave part of a secret behind)
   * and after.
   */
  static String abbreviateDetail(String detail) {
    String text = HTML_TAG.matcher(detail).replaceAll(" ");
    text = WHITESPACE.matcher(text).replaceAll(" ").trim();
    if (text.length() > MAX_DETAIL_LENGTH) {
      text = text.substring(0, MAX_DETAIL_LENGTH).trim() + "...";
    }
    return text;
  }

  /**
   * The exception to keep as the cause of a mapped error. The cause is printed with the error (job
   * profile, server.log), so when its server message contains a secret value it is replaced by an
   * exception of the same type with the redacted message and the original stack trace.
   */
  private static RuntimeException redactedCause(
      RuntimeException e, UnaryOperator<String> redactor) {
    String message = e.getMessage();
    String redacted = message == null ? null : redactor.apply(message);
    if (Objects.equals(message, redacted)) {
      return e;
    }
    RuntimeException copy =
        e instanceof NotAuthorizedException
            ? new NotAuthorizedException("%s", redacted)
            : new ForbiddenException("%s", redacted);
    copy.setStackTrace(e.getStackTrace());
    return copy;
  }

  /** Returns true when the REST catalog reports that a namespace is not empty with an HTTP 400. */
  static boolean isNamespaceNotEmpty(BadRequestException e) {
    String message = e.getMessage();
    return message != null && message.toLowerCase(Locale.ROOT).contains("not empty");
  }

  /**
   * Maps a namespace that cannot be dropped because it still has content to a validation error.
   * Covers both {@link NamespaceNotEmptyException} (HTTP 409, the REST spec) and an HTTP 400 whose
   * message says the namespace is not empty (Apache Polaris 1.x).
   */
  static UserException namespaceNotEmpty(RuntimeException e, Namespace namespace) {
    return UserException.validationError(e)
        .message(
            "Folder [%s] cannot be deleted because it is not empty. Drop the tables, views and"
                + " folders inside it first.",
            namespace)
        .buildSilently();
  }

  /** Maps a missing parent namespace on table creation to a validation error. */
  static UserException parentNamespaceNotFound(
      NoSuchNamespaceException e, TableIdentifier identifier) {
    return UserException.validationError(e)
        .message(
            "Cannot create [%s]: folder [%s] does not exist. Create the folder first.",
            identifier, identifier.namespace())
        .buildSilently();
  }

  /** Maps a missing parent namespace on folder (namespace) creation to a validation error. */
  static UserException parentNamespaceNotFound(NoSuchNamespaceException e, Namespace namespace) {
    Namespace parent =
        namespace.length() > 1
            ? Namespace.of(Arrays.copyOf(namespace.levels(), namespace.length() - 1))
            : Namespace.empty();
    return UserException.validationError(e)
        .message(
            "Cannot create folder [%s]: parent folder [%s] does not exist. Create the parent"
                + " folder first.",
            namespace, parent)
        .buildSilently();
  }

  /** Maps a name conflict on creation to a validation error. */
  static UserException alreadyExists(AlreadyExistsException e, TableIdentifier identifier) {
    return UserException.validationError(e)
        .message("A table or view with given name [%s] already exists.", identifier)
        .buildSilently();
  }

  /**
   * True when the 403 is about purging on drop rather than about a missing privilege. Matches the
   * Apache Polaris message ("Unable to purge entity ... drop-with-purge.enabled") but not privilege
   * names such as {@code DROP_TABLE_WITHOUT_PURGE}.
   */
  @VisibleForTesting
  static boolean isPurgeRelated(String message) {
    String lower = message.toLowerCase(Locale.ROOT);
    return lower.contains("unable to purge")
        || lower.contains("drop-with-purge")
        || lower.contains("drop_with_purge");
  }

  private static String asSentence(String message) {
    char last = message.charAt(message.length() - 1);
    return last == '.' || last == '!' || last == '?' ? message : message + ".";
  }

  private static String serverMessage(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message.trim();
  }
}
