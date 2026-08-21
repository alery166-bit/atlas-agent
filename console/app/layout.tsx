import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Atlas 企业风险研判工作台",
  description: "面向企业风险排查运营人员的对话式任务与报告工作台。",
  icons: {
    icon: "/favicon.svg",
    shortcut: "/favicon.svg",
  },
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
