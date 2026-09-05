import { pbkdf2, randomBytes, timingSafeEqual } from "node:crypto";
import { promisify } from "node:util";

export const LEGACY_PRIVACY_ITERATIONS = 120_000;
export const PRIVACY_ITERATIONS = 600_000;

export interface PrivacyCredential {
  salt: string;
  passcodeHash: string;
  iterations: number;
}

const deriveKey = promisify(pbkdf2);

export async function createPrivacyCredential(
  passcode: string
): Promise<PrivacyCredential> {
  const salt = randomBytes(16).toString("hex");
  const hash = await deriveKey(passcode, salt, PRIVACY_ITERATIONS, 32, "sha256");
  return {
    salt,
    passcodeHash: hash.toString("hex"),
    iterations: PRIVACY_ITERATIONS
  };
}

export async function verifyPrivacyPasscode(
  passcode: string,
  credential: PrivacyCredential
): Promise<boolean> {
  if (
    !/^[0-9a-f]{32}$/i.test(credential.salt) ||
    !/^[0-9a-f]{64}$/i.test(credential.passcodeHash) ||
    ![LEGACY_PRIVACY_ITERATIONS, PRIVACY_ITERATIONS].includes(credential.iterations)
  ) {
    return false;
  }
  // Legacy Web salts are hexadecimal text encoded as UTF-8, not decoded bytes.
  const actual = await deriveKey(
    passcode,
    credential.salt,
    credential.iterations,
    32,
    "sha256"
  );
  return timingSafeEqual(actual, Buffer.from(credential.passcodeHash, "hex"));
}
