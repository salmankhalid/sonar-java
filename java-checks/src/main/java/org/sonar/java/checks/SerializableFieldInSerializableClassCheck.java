/*
 * SonarQube Java
 * Copyright (C) 2012 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.java.checks;

import com.google.common.collect.ImmutableList;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.check.Priority;
import org.sonar.check.Rule;
import org.sonar.java.model.AbstractTypedTree;
import org.sonar.java.model.declaration.ClassTreeImpl;
import org.sonar.java.resolve.Symbol;
import org.sonar.java.resolve.Type;
import org.sonar.plugins.java.api.tree.ClassTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.Modifier;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.VariableTree;
import org.sonar.squidbridge.annotations.ActivatedByDefault;
import org.sonar.squidbridge.annotations.SqaleConstantRemediation;
import org.sonar.squidbridge.annotations.SqaleSubCharacteristic;

import javax.annotation.Nullable;
import java.util.List;

@Rule(
  key = "S1948",
  name = "Fields in a \"Serializable\" class should either be transient or serializable",
  tags = {"bug", "cwe", "serialization"},
  priority = Priority.CRITICAL)
@ActivatedByDefault
@SqaleSubCharacteristic(RulesDefinition.SubCharacteristics.DATA_RELIABILITY)
@SqaleConstantRemediation("30min")
public class SerializableFieldInSerializableClassCheck extends SubscriptionBaseVisitor {

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return ImmutableList.of(Tree.Kind.CLASS, Tree.Kind.ENUM);
  }

  @Override
  public void visitNode(Tree tree) {
    ClassTree classTree = (ClassTree) tree;
    if (isSerializable(classTree) && !hasSpecialHandlingSerializationMethods(classTree)) {
      for (Tree member : classTree.members()) {
        if (member.is(Tree.Kind.VARIABLE) && !isStatic((VariableTree) member) && !isTransientOrSerializable((VariableTree) member)) {
          addIssue(member, "Make \"" + ((VariableTree) member).simpleName().name() + "\" transient or serializable.");
        }
      }
    }
  }

  private boolean isStatic(VariableTree member) {
    return member.modifiers().modifiers().contains(Modifier.STATIC);
  }

  private boolean hasSpecialHandlingSerializationMethods(ClassTree classTree) {
    boolean hasWriteObject = false;
    boolean hasReadObject = false;
    for (Tree member : classTree.members()) {
      if (member.is(Tree.Kind.METHOD)) {
        MethodTree methodTree = (MethodTree) member;
        //FIXME detect methods based on type of arg and throws, not arity.
        if (methodTree.modifiers().modifiers().contains(Modifier.PRIVATE) && methodTree.parameters().size() == 1) {
          hasWriteObject |= "writeObject".equals(methodTree.simpleName().name()) && methodTree.throwsClauses().size() == 1;
          hasReadObject |= "readObject".equals(methodTree.simpleName().name()) && methodTree.throwsClauses().size() == 2;
        }
      }
    }
    return hasReadObject && hasWriteObject;
  }

  private boolean isTransientOrSerializable(VariableTree member) {
    return member.modifiers().modifiers().contains(Modifier.TRANSIENT) || isSerializable(member.type());
  }

  private boolean isSerializable(Tree tree) {
    if (tree.is(Tree.Kind.ENUM, Tree.Kind.PRIMITIVE_TYPE)) {
      return true;
    } else if (tree.is(Tree.Kind.CLASS)) {
      Symbol.TypeSymbol symbol = ((ClassTreeImpl) tree).getSymbol();
      if (symbol == null) {
        return false;
      }
      return implementsSerializable(symbol.getType());
    }
    return implementsSerializable(((AbstractTypedTree) tree).getSymbolType());
  }

  private boolean implementsSerializable(@Nullable Type type) {
    if (type == null || type.isTagged(Type.UNKNOWN)) {
      return false;
    }
    if (type.isTagged(Type.ARRAY)) {
      return implementsSerializable(((Type.ArrayType) type).elementType());
    }
    if (type.isTagged(Type.CLASS)) {
      Type.ClassType classType = (Type.ClassType) type;
      String interfaceName = classType.getSymbol().owner().getName() + "." + classType.getSymbol().getName();
      if ("java.io.Serializable".equals(interfaceName)) {
        return true;
      }
      return hasSupertypeSerializable((Type.ClassType) type);
    }
    if(type.isTagged(Type.TYPEVAR)) {
      return type.erasure().isSubtypeOf("java.io.Serializable");
    }
    return false;
  }

  private boolean hasSupertypeSerializable(Type.ClassType type) {
    Symbol.TypeSymbol symbol = type.getSymbol();
    for (Type interfaceType : symbol.getInterfaces()) {
      if (implementsSerializable(interfaceType)) {
        return true;
      }
    }
    return implementsSerializable(symbol.getSuperclass());
  }

}
