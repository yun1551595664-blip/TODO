import {
  createContext,
  type ReactNode,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import { Navigate, useLocation } from "react-router-dom";
import { issueApi } from "./api";
import type { AuthSession, AuthUser } from "./types";

export const AUTH_STORAGE_KEY = "issueOpsAuth";

type AuthContextValue = {
  session?: AuthSession;
  user?: AuthUser;
  loading: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
  hasPermission: (permission: string) => boolean;
};

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

function readStoredSession() {
  try {
    const raw = window.localStorage.getItem(AUTH_STORAGE_KEY);
    if (!raw) return undefined;
    const session = JSON.parse(raw) as AuthSession;
    if (!session.token || !session.user) return undefined;
    if (session.expiresAt && session.expiresAt * 1000 < Date.now()) return undefined;
    return session;
  } catch {
    return undefined;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<AuthSession | undefined>(() =>
    typeof window === "undefined" ? undefined : readStoredSession(),
  );
  const [loading, setLoading] = useState(() => Boolean(session));

  const logout = () => {
    window.localStorage.removeItem(AUTH_STORAGE_KEY);
    setSession(undefined);
  };

  useEffect(() => {
    if (!session) {
      setLoading(false);
      return;
    }
    let alive = true;
    issueApi
      .me()
      .then((user) => {
        if (!alive) return;
        const next = { ...session, user };
        window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(next));
        setSession(next);
      })
      .catch(() => {
        if (alive) logout();
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    const handleExpired = () => setSession(undefined);
    window.addEventListener("issueops:auth-expired", handleExpired);
    return () => window.removeEventListener("issueops:auth-expired", handleExpired);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      session,
      user: session?.user,
      loading,
      login: async (username, password) => {
        const next = await issueApi.login({ username, password });
        window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(next));
        setSession(next);
      },
      logout,
      hasPermission: (permission) =>
        Boolean(session?.user.permissions?.includes(permission)),
    }),
    [session, loading],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used within AuthProvider");
  return value;
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();
  if (loading) return <div className="page auth-loading">正在校验登录状态…</div>;
  if (!user) return <Navigate to="/login" replace state={{ from: location }} />;
  return children;
}

export function RequirePermission({
  permission,
  children,
}: {
  permission: string;
  children: ReactNode;
}) {
  const { hasPermission } = useAuth();
  if (!hasPermission(permission)) {
    return (
      <div className="page forbidden-page">
        <h1>无权访问</h1>
        <p>当前账号没有该模块的操作权限，请联系管理员调整角色。</p>
      </div>
    );
  }
  return children;
}
