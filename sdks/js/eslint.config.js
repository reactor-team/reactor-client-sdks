// @ts-check
import js from '@eslint/js';
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
    files: ['src/**/*.ts'],
  })),
  {
    files: ['src/**/*.ts'],
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
    files: ['scripts/**/*.mjs'],
    languageOptions: {
      globals: { console: 'readonly', process: 'readonly' },
    },
  },
  {
    // Single quotes everywhere; backticks stay reserved for actual
    // interpolation/multiline strings instead of doubling as a plain-string
    // style (allowTemplateLiterals defaults to false).
    rules: {
      quotes: ['error', 'single', { avoidEscape: true }],
    },
  },
);
