import { Navigate, Route, Routes } from "react-router-dom";
import { Suspense, lazy } from "react";
import { AuthProvider, RequireAuth, RequirePermission } from "./auth";

const AppLayout = lazy(() => import("./components/AppLayout"));
const Dashboard = lazy(() => import("./pages/Dashboard"));
const IssueList = lazy(() => import("./pages/IssueList"));
const IssueForm = lazy(() => import("./pages/IssueForm"));
const IssueDetail = lazy(() => import("./pages/IssueDetail"));
const DataReport = lazy(() => import("./pages/DataReport"));
const AiInsightPage = lazy(() => import("./pages/AiInsightPage"));
const FieldSettings = lazy(() => import("./pages/FieldSettings"));
const AccountSettings = lazy(() => import("./pages/AccountSettings"));
const RetrospectivePage = lazy(() => import("./pages/RetrospectivePage"));
const LoginPage = lazy(() => import("./pages/LoginPage"));

export default function App() {
  return (
    <AuthProvider>
      <Suspense fallback={<div className="page auth-loading">正在加载页面…</div>}>
        <Routes>
          <Route path="login" element={<LoginPage />} />
          <Route
            element={
              <RequireAuth>
                <AppLayout />
              </RequireAuth>
            }
          >
            <Route index element={<Dashboard />} />
            <Route path="issues" element={<IssueList />} />
            <Route path="issues/new" element={<IssueForm />} />
            <Route path="issues/:id/edit" element={<IssueForm />} />
            <Route path="issues/:id" element={<IssueDetail />} />
            <Route path="ai-insights" element={<AiInsightPage />} />
            <Route path="data" element={<DataReport />} />
            <Route path="data/analysis" element={<DataReport />} />
            <Route path="retrospective" element={<RetrospectivePage />} />
            <Route
              path="settings/fields"
              element={
                <RequirePermission permission="field:manage">
                  <FieldSettings />
                </RequirePermission>
              }
            />
            <Route
              path="settings/accounts"
              element={
                <RequirePermission permission="account:manage">
                  <AccountSettings />
                </RequirePermission>
              }
            />
            <Route
              path="knowledge"
              element={<Navigate to="/retrospective" replace />}
            />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </Suspense>
    </AuthProvider>
  );
}
