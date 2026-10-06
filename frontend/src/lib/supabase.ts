import { createClient, type SupabaseClient } from '@supabase/supabase-js'

/**
 * Supabase client for authentication. Both values come from the project's
 * Settings → API page in the Supabase dashboard and are safe to expose in the
 * browser (anon key, not the service role key).
 *
 * When either value is missing the app silently falls back to the built-in
 * email/password API — no Supabase code paths run at all.
 */
const supabaseUrl = (import.meta.env.VITE_SUPABASE_URL as string | undefined)?.trim()
const supabaseAnonKey = (import.meta.env.VITE_SUPABASE_ANON_KEY as string | undefined)?.trim()

export const supabaseEnabled = Boolean(supabaseUrl && supabaseAnonKey)

export const supabase: SupabaseClient | null = supabaseEnabled
  ? createClient(supabaseUrl as string, supabaseAnonKey as string, {
      auth: {
        persistSession: true,
        autoRefreshToken: true,
        detectSessionInUrl: true,
      },
    })
  : null

export interface SupabaseSignInResult {
  ok: boolean
  error?: string
}

function humanizeAuthError(message: string): string {
  if (/invalid login credentials/i.test(message)) return 'Invalid email or password'
  if (/email not confirmed/i.test(message)) return 'Please confirm your email first (check your inbox)'
  if (/user already registered/i.test(message)) return 'An account with this email already exists'
  if (/password should be at least/i.test(message)) return message
  if (/failed to fetch|networkerror/i.test(message)) return 'Cannot reach Supabase — check VITE_SUPABASE_URL'
  return message
}

export async function supabaseSignIn(email: string, password: string): Promise<SupabaseSignInResult> {
  if (!supabase) return { ok: false, error: 'Supabase is not configured' }
  const { error } = await supabase.auth.signInWithPassword({ email, password })
  if (error) return { ok: false, error: humanizeAuthError(error.message) }
  return { ok: true }
}

export async function supabaseSignUp(name: string, email: string, password: string): Promise<SupabaseSignInResult> {
  if (!supabase) return { ok: false, error: 'Supabase is not configured' }
  const { error } = await supabase.auth.signUp({
    email,
    password,
    options: { data: { full_name: name } },
  })
  if (error) return { ok: false, error: humanizeAuthError(error.message) }
  return { ok: true }
}

export async function supabaseRequestPasswordReset(email: string): Promise<SupabaseSignInResult> {
  if (!supabase) return { ok: false, error: 'Supabase is not configured' }
  const redirectTo = `${window.location.origin}/login`
  const { error } = await supabase.auth.resetPasswordForEmail(email, { redirectTo })
  if (error) return { ok: false, error: humanizeAuthError(error.message) }
  return { ok: true }
}

export async function supabaseSignOut(): Promise<void> {
  if (!supabase) return
  await supabase.auth.signOut()
}
