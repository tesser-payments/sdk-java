package xyz.tesser.sdk.java.internal.signing;

/**
 * One {@code accounts[]} element of an {@code ACTIVITY_TYPE_CREATE_WALLET} activity.
 *
 * @param curve e.g. {@code CURVE_SECP256K1}, {@code CURVE_ED25519}
 * @param pathFormat {@code PATH_FORMAT_BIP32} or {@code PATH_FORMAT_BIP44}
 * @param path BIP32 derivation path
 * @param addressFormat e.g. {@code ADDRESS_FORMAT_ETHEREUM}
 */
public record AccountSpec(String curve, String pathFormat, String path, String addressFormat) {}
