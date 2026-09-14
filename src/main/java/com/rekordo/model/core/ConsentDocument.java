package com.rekordo.model.core;

/**
 * A statement a person can be asked to agree to, and the version of it that is current.
 *
 * The version lives here rather than in the request because the server has to be the one
 * that knows: a client three releases old would otherwise record consent to a document it
 * has never seen. It mirrors the versions in the shared package's legal module -- move one
 * and move the other, or the app will show a document whose number does not match the
 * record of accepting it.
 */
public enum ConsentDocument {

    /** The Nutzungsbedingungen. */
    TERMS("1.0"),

    /** The Datenschutzerklärung, which is read rather than agreed to -- the record is the same. */
    PRIVACY("1.1"),

    /**
     * "I am 16 or older", the age at which Art. 8 DSGVO stops asking for a parent in Germany.
     * It is not a document, so it carries the version of the terms that worded it.
     */
    AGE("1.0");

    private final String currentVersion;

    ConsentDocument(String currentVersion) {
        this.currentVersion = currentVersion;
    }

    public String currentVersion() {
        return currentVersion;
    }
}
