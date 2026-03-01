import React, { useCallback, useEffect, useState } from 'react';
import { authAPI, describeApiError, getApiBase, setAuthToken } from '../services/api';
import './LoginPage.css';

const isEmail = (value) => /\S+@\S+\.\S+/.test(value);
const BACKEND_RETRY_MS_DOWN = 2500;
const BACKEND_RETRY_MS_UP = 12000;

function LoginPage({
  onAuthenticated,
  initialStatus = '',
  onRetryBackend,
  mobilePreviewEnabled = false,
  onToggleMobilePreview
}) {
  const [mode, setMode] = useState('email');
  const [name, setName] = useState('');
  const [nickname, setNickname] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [step, setStep] = useState('request');
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('');
  const [devCode, setDevCode] = useState('');
  const [maskedTarget, setMaskedTarget] = useState('');
  const [expiresAt, setExpiresAt] = useState('');
  const [backendReady, setBackendReady] = useState(false);
  const [backendChecking, setBackendChecking] = useState(true);
  const [backendHint, setBackendHint] = useState(() => getApiBase());

  const probeBackend = useCallback(
    async (showFailureMessage = false) => {
      setBackendHint(getApiBase());
      try {
        setBackendChecking(true);
        await authAPI.ping(2400);
        setBackendHint(getApiBase());
        setBackendReady(true);
        setStatus((previous) => {
          const text = String(previous || '').toLowerCase();
          if (text.includes('backend') || text.includes('unreachable') || text.includes('timed out')) {
            return '';
          }
          return previous;
        });
        return true;
      } catch (error) {
        setBackendHint(getApiBase());
        setBackendReady(false);
        if (showFailureMessage) {
          setStatus(describeApiError(error, `Unable to reach backend at ${getApiBase()}.`));
        }
        return false;
      } finally {
        setBackendChecking(false);
      }
    },
    []
  );

  useEffect(() => {
    if (initialStatus) {
      setStatus(initialStatus);
    }
  }, [initialStatus]);

  useEffect(() => {
    let timer;
    probeBackend(Boolean(initialStatus));
    timer = window.setInterval(() => {
      if (typeof document === 'undefined' || document.visibilityState === 'visible') {
        probeBackend(false);
      }
    }, backendReady ? BACKEND_RETRY_MS_UP : BACKEND_RETRY_MS_DOWN);
    return () => {
      if (timer) {
        window.clearInterval(timer);
      }
    };
  }, [initialStatus, probeBackend, backendReady]);

  const resetVerificationState = () => {
    setStep('request');
    setCode('');
    setDevCode('');
    setMaskedTarget('');
    setExpiresAt('');
  };

  const handleRequestCode = async (event) => {
    event.preventDefault();
    setStatus('');

    if (!backendReady) {
      const readyNow = await probeBackend(true);
      if (!readyNow) {
        return;
      }
    }

    const payload = {
      name: name.trim() || undefined,
      nickname: nickname.trim() || undefined
    };

    if (mode === 'email') {
      if (!isEmail(email.trim())) {
        setStatus('Enter a valid email to continue.');
        return;
      }
      payload.email = email.trim();
    } else {
      const trimmedPhone = phone.trim();
      if (trimmedPhone.length < 8) {
        setStatus('Enter a valid phone number to continue.');
        return;
      }
      payload.phone = trimmedPhone;
    }

    try {
      setBusy(true);
      const response = await authAPI.requestCode(payload);
      setStep('verify');
      setDevCode(response?.data?.devCode || '');
      setMaskedTarget(response?.data?.targetMasked || '');
      setExpiresAt(response?.data?.expiresAt || '');
      setStatus('Verification code generated. Enter the code to sign in.');
    } catch (error) {
      setStatus(describeApiError(error, 'Unable to generate verification code.'));
    } finally {
      setBusy(false);
    }
  };

  const handleVerify = async (event) => {
    event.preventDefault();
    setStatus('');

    if (!backendReady) {
      const readyNow = await probeBackend(true);
      if (!readyNow) {
        return;
      }
    }

    if (code.trim().length < 4) {
      setStatus('Enter the verification code.');
      return;
    }

    const payload = {
      code: code.trim(),
      name: name.trim() || undefined,
      nickname: nickname.trim() || undefined
    };

    if (mode === 'email') {
      payload.email = email.trim();
    } else {
      payload.phone = phone.trim();
    }

    try {
      setBusy(true);
      const response = await authAPI.verifyCode(payload);
      const token = response?.data?.token || '';
      const user = response?.data?.user || null;
      if (!token || !user?.id) {
        setStatus('Login response is incomplete. Try again.');
        return;
      }

      setAuthToken(token);
      onAuthenticated?.(user, token);
    } catch (error) {
      setStatus(describeApiError(error, 'Verification failed.'));
    } finally {
      setBusy(false);
    }
  };

  const handleGuestLogin = async () => {
    setStatus('');

    if (!backendReady) {
      const readyNow = await probeBackend(true);
      if (!readyNow) {
        return;
      }
    }

    try {
      setBusy(true);
      const response = await authAPI.guest({
        nickname: nickname.trim() || 'Guest'
      });
      const token = response?.data?.token || '';
      const user = response?.data?.user || null;
      if (!token || !user?.id) {
        setStatus('Guest login failed. Try again.');
        return;
      }

      setAuthToken(token);
      onAuthenticated?.(user, token);
    } catch (error) {
      setStatus(describeApiError(error, 'Unable to start guest mode.'));
    } finally {
      setBusy(false);
    }
  };

  const handleRetryBackend = async () => {
    setStatus('');
    if (onRetryBackend) {
      await onRetryBackend();
    }
    await probeBackend(true);
  };

  return (
    <div className={`login-shell ${mobilePreviewEnabled ? 'is-mobile-preview' : ''}`}>
      <section className="login-card">
        <button
          type="button"
          className={`login-mobile-toggle ${mobilePreviewEnabled ? 'is-active' : ''}`}
          onClick={() => onToggleMobilePreview?.(!mobilePreviewEnabled)}
        >
          {mobilePreviewEnabled ? 'Desktop View' : 'Mobile View'}
        </button>
        <p className="login-eyebrow">Scholarship Build</p>
        <h1>Calorie Tracker Login</h1>
        <p className="login-subtitle">
          Sign in with email or phone OTP. Your profile, entries, and preferences stay in MySQL.
        </p>
        <p className="login-subtitle">
          New users can try <strong>Guest Mode</strong> first to explore the app instantly.
        </p>

        <div className="login-mode-switch">
          <button
            type="button"
            className={mode === 'email' ? 'active' : ''}
            onClick={() => {
              setMode('email');
              resetVerificationState();
            }}
          >
            Email OTP
          </button>
          <button
            type="button"
            className={mode === 'phone' ? 'active' : ''}
            onClick={() => {
              setMode('phone');
              resetVerificationState();
            }}
          >
            Phone OTP
          </button>
        </div>

        {step === 'request' ? (
          <form className="login-form" onSubmit={handleRequestCode}>
            <div className="login-grid-two">
              <label>
                Full name
                <input
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                  placeholder="Your full name"
                />
              </label>
              <label>
                Nickname (editable later)
                <input
                  value={nickname}
                  onChange={(event) => setNickname(event.target.value)}
                  placeholder="What should app call you?"
                />
              </label>
            </div>

            {mode === 'email' ? (
              <label>
                Email
                <input
                  value={email}
                  type="email"
                  onChange={(event) => setEmail(event.target.value)}
                  placeholder="you@example.com"
                />
              </label>
            ) : (
              <label>
                Phone number
                <input
                  value={phone}
                  onChange={(event) => setPhone(event.target.value)}
                  placeholder="+91xxxxxxxxxx"
                />
              </label>
            )}

            <button className="primary-btn" type="submit" disabled={busy}>
              {busy ? 'Requesting OTP...' : 'Send OTP'}
            </button>
            <button
              type="button"
              className="ghost-btn"
              onClick={handleGuestLogin}
              disabled={busy}
            >
              Continue as Guest
            </button>
          </form>
        ) : (
          <form className="login-form" onSubmit={handleVerify}>
            <div className="login-code-head">
              <h3>Verify OTP</h3>
              <small>{maskedTarget || (mode === 'email' ? email : phone)}</small>
            </div>

            <label>
              Enter code
              <input
                value={code}
                onChange={(event) => setCode(event.target.value)}
                placeholder="6-digit code"
                inputMode="numeric"
              />
            </label>

            {!!devCode && (
              <div className="login-dev-otp">
                Dev OTP: <strong>{devCode}</strong>
                {expiresAt ? (
                  <small>
                    Expires at {new Date(expiresAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                  </small>
                ) : null}
              </div>
            )}

            <div className="login-actions">
              <button className="primary-btn" type="submit" disabled={busy}>
                {busy ? 'Verifying...' : 'Login'}
              </button>
              <button type="button" className="secondary-btn" onClick={resetVerificationState} disabled={busy}>
                Change contact
              </button>
            </div>
          </form>
        )}

        <div className={`login-backend-state ${backendReady ? 'is-up' : 'is-down'}`}>
          {backendChecking
            ? 'Checking backend...'
            : backendReady
              ? 'Backend connected'
              : 'Backend not reachable yet. Retrying automatically...'}
        </div>

        {status && <div className="status-line">{status}</div>}
        <div className="login-hint-row">
          <p className="login-hint">Backend endpoint: {backendHint}</p>
          <button
            type="button"
            className="secondary-btn login-retry-btn"
            onClick={handleRetryBackend}
            disabled={busy || backendChecking}
          >
            Retry backend
          </button>
        </div>
      </section>
    </div>
  );
}

export default LoginPage;
