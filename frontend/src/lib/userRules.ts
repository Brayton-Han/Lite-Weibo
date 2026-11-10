// 与后端 dto/UserConstraints.java 保持一致；前端只做预校验，以后端 400 为准

export const USERNAME_MIN = 2;
export const USERNAME_MAX = 20;
// 任意语言的字母、数字、下划线
const USERNAME_RE = /^[\p{L}\p{N}_]+$/u;
export const PASSWORD_MIN = 8;
export const PASSWORD_MAX = 72;
export const EMAIL_MAX = 254;
export const BIO_MAX = 160;

/** 返回错误提示，合法时返回 null */
export function validateUsername(username: string): string | null {
  // 按 UTF-16 码元计数，与后端 @Size 和 input maxLength 一致
  const len = username.length;
  if (len < USERNAME_MIN || len > USERNAME_MAX || !USERNAME_RE.test(username)) {
    return `Username must be ${USERNAME_MIN}-${USERNAME_MAX} characters: letters, digits or underscore.`;
  }
  return null;
}
