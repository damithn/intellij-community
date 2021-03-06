// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.intention.impl;

import com.intellij.codeInsight.CodeInsightUtil;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.util.IntentionFamilyName;
import com.intellij.codeInspection.util.IntentionName;
import com.intellij.java.JavaBundle;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.codeStyle.VariableKind;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.IncorrectOperationException;
import com.siyeh.ig.psiutils.EquivalenceChecker;
import com.siyeh.ig.psiutils.VariableNameGenerator;
import one.util.streamex.MoreCollectors;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.intellij.util.ObjectUtils.tryCast;

public class CollapseIntoLoopAction implements IntentionAction {
  @Override
  public @IntentionName @NotNull String getText() {
    return JavaBundle.message("intention.name.collapse.into.loop");
  }

  @Override
  public @NotNull @IntentionFamilyName String getFamilyName() {
    return getText();
  }

  private static List<PsiStatement> extractStatements(Editor editor, PsiFile file) {
    if (!(file instanceof PsiJavaFile) || !PsiUtil.isLanguageLevel5OrHigher(file)) return Collections.emptyList();
    SelectionModel model = editor.getSelectionModel();
    int startOffset = model.getSelectionStart();
    int endOffset = model.getSelectionEnd();
    PsiElement[] elements = CodeInsightUtil.findStatementsInRange(file, startOffset, endOffset);
    return StreamEx.of(elements)
      .map(e -> tryCast(e, PsiStatement.class))
      .collect(MoreCollectors.ifAllMatch(Objects::nonNull, Collectors.toList()))
      .orElse(Collections.emptyList());
  }

  @Override
  public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
    List<PsiStatement> statements = extractStatements(editor, file);
    return LoopModel.from(statements) != null;
  }

  @Override
  public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
    List<PsiStatement> statements = extractStatements(editor, file);
    LoopModel model = LoopModel.from(statements);
    if (model == null) return;
    model.generate();
  }

  @Override
  public boolean startInWriteAction() {
    return true;
  }

  private static class LoopModel {
    final @NotNull List<PsiExpression> myLoopElements;
    final @NotNull List<PsiExpression> myExpressionsToReplace;
    final @NotNull List<PsiStatement> myStatements;
    final int myStatementCount;
    final @Nullable PsiType myType;

    private LoopModel(@NotNull List<PsiExpression> elements,
                      @NotNull List<PsiExpression> expressionsToReplace,
                      @NotNull List<PsiStatement> statements,
                      int count,
                      @Nullable PsiType type) {
      myLoopElements = elements;
      myExpressionsToReplace = expressionsToReplace;
      myStatements = statements;
      myStatementCount = count;
      myType = type;
    }

    void generate() {
      PsiStatement context = myStatements.get(0);
      PsiElementFactory factory = JavaPsiFacade.getElementFactory(context.getProject());
      String loopDeclaration;
      String varName;
      if (myType == null) {
        int size = myStatements.size() / myStatementCount;
        varName = new VariableNameGenerator(context, VariableKind.PARAMETER).byType(PsiType.INT).generate(true);
        loopDeclaration = "for(int " + varName + "=0;" + varName + "<" + size + ";" + varName + "++)";
      }
      else {
        varName = new VariableNameGenerator(context, VariableKind.PARAMETER).byType(myType).generate(true);
        loopDeclaration = tryCollapseIntoCountingLoop(varName);
        if (loopDeclaration == null) {
          String container;
          if (myType instanceof PsiPrimitiveType) {
            container = "new " + myType.getCanonicalText() + "[]{" + StringUtil.join(myLoopElements, PsiElement::getText, ",") + "}";
          }
          else {
            container = CommonClassNames.JAVA_UTIL_ARRAYS + ".asList(" + StringUtil.join(myLoopElements, PsiElement::getText, ",") + ")";
          }
          loopDeclaration = "for(" + myType.getCanonicalText() + " " + varName + ":" + container + ")";
        }
      }
      PsiLoopStatement loop = (PsiLoopStatement)factory.createStatementFromText(loopDeclaration + " {}", context);

      PsiCodeBlock block = ((PsiBlockStatement)Objects.requireNonNull(loop.getBody())).getCodeBlock();
      PsiJavaToken brace = Objects.requireNonNull(block.getRBrace());

      PsiExpression ref = factory.createExpressionFromText(varName, context);
      myExpressionsToReplace.forEach(expr -> expr.replace(ref));
      block.addRangeBefore(myStatements.get(0), myStatements.get(myStatementCount - 1), brace);
      PsiElement origBlock = context.getParent();
      JavaCodeStyleManager.getInstance(block.getProject()).shortenClassReferences(origBlock.addBefore(loop, myStatements.get(0)));
      origBlock.deleteChildRange(myStatements.get(0), myStatements.get(myStatements.size() - 1));
    }

    private String tryCollapseIntoCountingLoop(String varName) {
      if (!PsiType.INT.equals(myType) && !PsiType.LONG.equals(myType)) return null;
      Long start = null;
      Long step = null;
      Long last = null;
      for (PsiExpression element : myLoopElements) {
        if (!(element instanceof PsiLiteralExpression)) return null;
        Object value = ((PsiLiteralExpression)element).getValue();
        if (!(value instanceof Integer) && !(value instanceof Long)) return null;
        long cur = ((Number)value).longValue();
        if (start == null) {
          start = cur;
        }
        else if (step == null) {
          step = cur - start;
          if (step == 0) return null;
        }
        else if (cur - last != step || (step > 0 && cur < last) || (step < 0 && cur > last)) {
          return null;
        }
        last = cur;
      }
      if (start == null || step == null) return null;
      String suffix = PsiType.LONG.equals(myType) ? "L" : "";
      String initial = myType.getCanonicalText() + " " + varName + "=" + start + suffix;
      String condition =
        varName + (step == 1 && last != (PsiType.LONG.equals(myType) ? Long.MAX_VALUE : Integer.MAX_VALUE) ? "<" + (last + 1) :
                   step == -1 && last != (PsiType.LONG.equals(myType) ? Long.MIN_VALUE : Integer.MIN_VALUE) ? ">" + (last - 1) :
                   (step < 0 ? ">=" : "<=") + last) + suffix;
      String increment = varName + (step == 1 ? "++" : step == -1 ? "--" : step > 0 ? "+=" + step + suffix : "-=" + (-step) + suffix);
      return "for(" + initial + ";" + condition + ";" + increment + ")";
    }

    static @Nullable LoopModel from(List<PsiStatement> statements) {
      int size = statements.size();
      if (size <= 1 || size > 1000) return null;
      if (!(statements.get(0).getParent() instanceof PsiCodeBlock)) return null;
      for (int count = 1; count <= size / 2; count++) {
        if (size % count != 0) continue;
        LoopModel model = from(statements, count);
        if (model != null) {
          return model;
        }
      }
      return null;
    }

    private static @Nullable LoopModel from(List<PsiStatement> statements, int count) {
      EquivalenceChecker equivalence = EquivalenceChecker.getCanonicalPsiEquivalence();
      int size = statements.size();
      PsiType type = null;
      List<PsiExpression> expressionsToReplace = new ArrayList<>();
      List<PsiExpression> expressionsToIterate = new ArrayList<>();
      boolean secondIteration = true;
      for (int offset = count; offset < size; offset += count) {
        PsiExpression firstIterationExpression = null;
        PsiExpression curIterationExpression = null;
        for (int index = 0; index < count; index++) {
          PsiStatement first = statements.get(index);
          PsiStatement cur = statements.get(index + offset);
          EquivalenceChecker.Match match = equivalence.statementsMatch(first, cur);
          if (match.isExactMismatch()) return null;
          if (match.isExactMatch()) continue;
          PsiElement leftDiff = match.getLeftDiff();
          PsiElement rightDiff = match.getRightDiff();
          if (!(leftDiff instanceof PsiExpression) || !(rightDiff instanceof PsiExpression)) return null;
          curIterationExpression = (PsiExpression)rightDiff;
          firstIterationExpression = (PsiExpression)leftDiff;
          if (secondIteration) {
            if (!expressionsToReplace.isEmpty() &&
                !equivalence.expressionsAreEquivalent(expressionsToReplace.get(0), (PsiExpression)leftDiff)) {
              return null;
            }
            expressionsToReplace.add((PsiExpression)leftDiff);
          }
          else {
            if (!expressionsToReplace.contains(leftDiff)) return null;
          }
        }
        if (secondIteration) {
          if (firstIterationExpression != null) {
            expressionsToIterate.add(firstIterationExpression);
            PsiType expressionType = GenericsUtil.getVariableTypeByExpressionType(firstIterationExpression.getType());
            if (expressionType == null) return null;
            type = expressionType;
          }
        }
        secondIteration = false;
        if (curIterationExpression != null) {
          expressionsToIterate.add(curIterationExpression);
        }
      }
      return new LoopModel(expressionsToIterate, expressionsToReplace, statements, count, type);
    }
  }
}
