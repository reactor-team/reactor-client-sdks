// @ts-check
import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import stylistic from '@stylistic/eslint-plugin';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist/**', 'demo/dist/**', 'demo/node_modules/**', 'node_modules/**'] },
  js.configs.recommended,
  // Non-type-checked TS parsing everywhere (so tsup.config.ts etc. parse at
  // all), then type-checked rules layered on top for the SDK's own source
  // only — config/build scripts aren't part of tsconfig.json's `include`
  // and have no project to type-check against.
  ...tseslint.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked.map((config) => ({
    ...config,
    files: ['src/**/*.ts', 'src/**/*.tsx'],
  })),
  {
    files: ['src/**/*.ts', 'src/**/*.tsx'],
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      '@typescript-eslint/consistent-type-imports': 'error',
    },
  },
  {
    files: ['src/react/**/*.ts', 'src/react/**/*.tsx'],
    plugins: { 'react-hooks': reactHooks },
    rules: reactHooks.configs.recommended.rules,
  },
  {
    files: ['scripts/**/*.mjs'],
    languageOptions: {
      globals: { console: 'readonly', process: 'readonly' },
    },
  },
  {
    plugins: {
      '@stylistic': stylistic,
    },
    rules: {
      // Single quotes everywhere; backticks stay reserved for actual
      // interpolation/multiline strings instead of doubling as a
      // plain-string style (allowTemplateLiterals defaults to false).
      quotes: ['error', 'single', { avoidEscape: true }],
      // Always brace if/else/for/while bodies, even one-liners.
      curly: ['error', 'all'],
      // Blank line after a const/let/var, unless followed by another
      // const/let/var (so a run of declarations can stay tight).
      '@stylistic/padding-line-between-statements': [
        'error',
        { blankLine: 'always', prev: ['const', 'let', 'var'], next: '*' },
        { blankLine: 'any', prev: ['const', 'let', 'var'], next: ['const', 'let', 'var'] },
      ],
    },
  },
);
