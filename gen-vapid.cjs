/*
 * Temporary: generates the VAPID key pair Web Push needs, in the raw form
 * `web-push generate-vapid-keys` produces — a 65-byte uncompressed P-256 point
 * and the 32-byte scalar, both base64url.
 *
 * The public half is printed; the private half is only ever written to the
 * gitignored file, so it never appears in a transcript or a commit.
 */
const { generateKeyPairSync } = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')

const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })

const jwk = publicKey.export({ format: 'jwk' })
const decode = (value) => Buffer.from(value.replace(/-/g, '+').replace(/_/g, '/'), 'base64')
const encode = (buffer) => buffer.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')

// The browser takes `applicationServerKey` as the uncompressed point, so the
// 0x04 prefix and both coordinates are concatenated here rather than left as JWK.
const publicRaw = Buffer.concat([Buffer.from([0x04]), decode(jwk.x), decode(jwk.y)])
const privateRaw = decode(privateKey.export({ format: 'jwk' }).d)

if (publicRaw.length !== 65 || privateRaw.length !== 32) {
  throw new Error(`unexpected key sizes: ${publicRaw.length} and ${privateRaw.length}`)
}

const publicB64 = encode(publicRaw)
const privateB64 = encode(privateRaw)

const file = path.join(__dirname, '.env.push.local')
fs.writeFileSync(
  file,
  [
    '# Nexus Web Push — VAPID key pair, generated locally and NOT committed.',
    '# Copy both lines into Railway → the backend service → Variables.',
    '# The private key is a credential: treat it like a password.',
    `NEXUS_PUSH_VAPID_PUBLIC_KEY=${publicB64}`,
    `NEXUS_PUSH_VAPID_PRIVATE_KEY=${privateB64}`,
    '',
  ].join('\n'),
)

// A short, non-reversible fingerprint is enough to confirm the right key is
// deployed later without exposing it.
const fingerprint = require('node:crypto').createHash('sha256').update(privateRaw).digest('hex').slice(0, 12)

console.log('public key (safe to share, served to browsers):')
console.log(publicB64)
console.log('lengths: public', publicRaw.length, 'private', privateRaw.length)
console.log('written to', path.relative(process.cwd(), file))
console.log('private key fingerprint (sha256, first 12 hex):', fingerprint)
