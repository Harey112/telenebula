import tseslint from "typescript-eslint";
import solid from "eslint-plugin-solid";

export default tseslint.config(
  { ignores: ["build/**", "node_modules/**", "test-results/**", "playwright-report/**"] },
  ...tseslint.configs.recommended,
  solid.configs["flat/recommended"],
  {
    files: ["**/*.{ts,tsx}"],
    rules: {
      "@typescript-eslint/no-explicit-any": "error",
      "@typescript-eslint/no-non-null-assertion": "error",
      "@typescript-eslint/ban-ts-comment": "error",
      "no-restricted-syntax": [
        "error",
        { selector: "MemberExpression[property.name='innerHTML']", message: "Render text through JSX." },
        { selector: "NewExpression[callee.name='Function']", message: "Dynamic code is not allowed." },
      ],
      "no-eval": "error",
    },
  },
);
