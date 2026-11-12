import {
  createContext,
  useCallback,
  useContext,
  useState,
  type ReactNode,
} from "react";

interface AuthResponse {
  userId: string;
  fullName: string;
  email: string;
  role: string;
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}

export interface AuthUser {
  userId: string;
  fullName: string;
  email: string;
  role: string;
}

interface AuthContextValue {
  user: AuthUser | null;
  accessToken: string | null;
  isAuthenticated: boolean;

  login: (email: string, password: string) => Promise<void>;

  register: (
    fullName: string,
    email: string,
    password: string,
  ) => Promise<void>;

  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

async function readError(response: Response): Promise<string> {
  try {
    const data = await response.json();

    if (typeof data.detail === "string") {
      return data.detail;
    }

    if (typeof data.message === "string") {
      return data.message;
    }
  } catch {
    // Fall back to HTTP status.
  }

  return `Request failed (HTTP ${response.status})`;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);

  const [accessToken, setAccessToken] = useState<string | null>(null);

  const [expiresAt, setExpiresAt] = useState<number | null>(null);

  const clearAuth = useCallback(() => {
    setUser(null);
    setAccessToken(null);
    setExpiresAt(null);
  }, []);

  const acceptAuth = useCallback((data: AuthResponse) => {
    setUser({
      userId: data.userId,
      fullName: data.fullName,
      email: data.email,
      role: data.role,
    });

    setAccessToken(data.accessToken);

    setExpiresAt(Date.now() + data.expiresIn * 1000);
  }, []);

  const login = useCallback(
    async (email: string, password: string) => {
      const response = await fetch("/api/auth/login", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          email,
          password,
        }),
      });

      if (!response.ok) {
        throw new Error(await readError(response));
      }

      const data: AuthResponse = await response.json();

      acceptAuth(data);
    },
    [acceptAuth],
  );

  const register = useCallback(
    async (fullName: string, email: string, password: string) => {
      const response = await fetch("/api/auth/register", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          fullName,
          email,
          password,
        }),
      });

      if (!response.ok) {
        throw new Error(await readError(response));
      }

      const data: AuthResponse = await response.json();

      acceptAuth(data);
    },
    [acceptAuth],
  );

  const tokenIsValid = !!accessToken && !!expiresAt && Date.now() < expiresAt;

  const isAuthenticated = user !== null && tokenIsValid;

  const value: AuthContextValue = {
    user: isAuthenticated ? user : null,
    accessToken: tokenIsValid ? accessToken : null,
    isAuthenticated,

    login,
    register,
    logout: clearAuth,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);

  if (!context) {
    throw new Error("useAuth must be used inside AuthProvider");
  }

  return context;
}
