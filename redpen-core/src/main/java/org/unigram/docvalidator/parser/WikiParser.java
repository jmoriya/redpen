/**
 * redpen: a text inspection tool
 * Copyright (C) 2014 Recruit Technologies Co., Ltd. and contributors
 * (see CONTRIBUTORS.md)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.unigram.docvalidator.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;

import org.apache.commons.io.IOUtils;
import org.unigram.docvalidator.model.Document;
import org.unigram.docvalidator.model.Paragraph;
import org.unigram.docvalidator.model.Section;
import org.unigram.docvalidator.model.Sentence;
import org.unigram.docvalidator.DocumentValidatorException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parser for wiki formatted file.
 */
@SuppressWarnings("ALL")
public final class WikiParser extends BasicDocumentParser {
  /**
   * Constructor.
   */
  WikiParser() {
    super();
  }

  public Document generateDocument(String fileName)
      throws DocumentValidatorException {
    InputStream inputStream = this.loadStream(fileName);
    return this.generateDocument(inputStream);
  }

  public Document generateDocument(InputStream is)
      throws DocumentValidatorException{
    BufferedReader br = null;

    Document document = new Document();
    // for sentences right below the beginning of document
    List<Sentence> headers = new ArrayList<Sentence>();
    headers.add(new Sentence("", 0));
    Section currentSection = new Section(0, headers);
    document.appendSection(currentSection);

    // begin parsing
    LinePattern prevPattern, currentPattern = LinePattern.VOID;
    String line;
    int lineNum = 0;
    StringBuilder remain = new StringBuilder();
    try {
      br = createReader(is);
      while ((line = br.readLine()) != null) {
        prevPattern = currentPattern;
        List<String> head = new ArrayList<String>();
        if (currentPattern == LinePattern.COMMENT) {
          if (check(END_COMMENT_PATTERN, line, head)) {
            currentPattern = LinePattern.VOID;
          }
        } else if (check(HEADER_PATTERN, line, head)) {
          currentPattern = LinePattern.HEADER;
          currentSection = appendSection(document, currentSection, head,
              lineNum);
        } else if (check(LIST_PATTERN, line, head)) {
          currentPattern = LinePattern.LIST;
          appendListElement(currentSection, prevPattern, head, lineNum);
        } else if (check(NUMBERED_LIST_PATTERN, line, head)) {
          currentPattern = LinePattern.LIST;
          appendListElement(currentSection, prevPattern, head, lineNum);
        } else if (check(BEGIN_COMMENT_PATTERN, line, head)) {
          if (!check(END_COMMENT_PATTERN, line, head)) { // skip comment
            currentPattern = LinePattern.COMMENT;
          }
        } else if (line.equals("")) { // new paragraph content
          currentSection.appendParagraph(new Paragraph());
        } else { // usual sentence.
          currentPattern = LinePattern.SENTENCE;
          String remainStr = appendSentencesIntoSection(lineNum,
              remain.append(line).toString(),
              currentSection);
          remain.delete(0, remain.length());
          remain.append(remainStr);
        }
        lineNum++;
      }
    } catch (IOException e) {
      throw new DocumentValidatorException("Failed to parse input document: " + e.getMessage());
    } finally {
      IOUtils.closeQuietly(br);
    }

    if (remain.length() > 0) {
      appendLastSentence(document, lineNum, remain.toString());
    }
    return document;
  }

  private void appendListElement(Section currentSection,
      LinePattern prevPattern, List<String> head, int lineNum) {
    if (prevPattern != LinePattern.LIST) {
      currentSection.appendListBlock();
    }
    List<Sentence> outputSentences = new ArrayList<Sentence>();
    String remainSentence = obtainSentences(0, head.get(1), outputSentences);
    currentSection.appendListElement(extractListLevel(head.get(0)),
        outputSentences);
    // NOTE: for list content without period
    if (remainSentence != null && remainSentence.length() > 0) {
      outputSentences.add(new Sentence(remainSentence, lineNum));
    }
  }

  private Section appendSection(Document document,
      Section currentSection, List<String> head, int lineNum) {
    Integer level = Integer.valueOf(head.get(0));
    List<Sentence> outputSentences = new ArrayList<Sentence>();
    String remainHeader =
        obtainSentences(lineNum, head.get(1), outputSentences);
    // NOTE: for header without period
    if (remainHeader != null && remainHeader.length() > 0) {
      outputSentences.add(new Sentence(remainHeader, lineNum));
    }

    // To deal with header content as a paragraph
    if (outputSentences.size() > 0) {
      outputSentences.get(0).isFirstSentence = true;
    }

    Section tmpSection =  new Section(level, outputSentences);
    document.appendSection(tmpSection);
    if (!addChild(currentSection, tmpSection)) {
      LOG.warn("Failed to add parent for a Seciotn: "
          + tmpSection.getHeaderContents().next());
    }
    currentSection = tmpSection;
    return currentSection;
  }

  private void appendLastSentence(Document doc, int lineNum, String remain) {
    Sentence sentence = new Sentence(remain, lineNum);
    parseSentence(sentence); // extract inline elements
    doc.getLastSection().appendSentence(sentence);
  }

  private void parseSentence(Sentence sentence) {
    extractLinks(sentence);
    removeTags(sentence);
  }

  private void removeTags(Sentence sentence) {
    String content = sentence.content;
    for (Pattern inlinePattern : INLINE_PATTERNS) {
      Matcher m = inlinePattern.matcher(content);
      content = m.replaceAll("$1");
    }
    sentence.content = content;
  }

  private void extractLinks(Sentence sentence) {
    StringBuilder modContent = new StringBuilder();
    int start = 0;
    Matcher m = LINK_PATTERN.matcher(sentence.content);
    while (m.find()) {
      String[] tagInternal = m.group(1).split("\\|");
      String tagURL = null;
      if (tagInternal.length == 1) {
        tagURL = tagInternal[0].trim();
        modContent.append(sentence.content.substring(
            start, m.start())).append(tagURL.trim());
      } else if (tagInternal.length == 0) {
        LOG.warn("Invalid link block: vacant block");
        tagURL = "";
      } else {
        if (tagInternal.length > 2) {
          LOG.warn(
              "Invalid link block: there are more than two link blocks at line "
                  + sentence.position);
        }
        tagURL = tagInternal[1].trim();
        StringBuilder buffer = new StringBuilder();
        buffer.append(sentence.content.substring(start, m.start()));
        buffer.append(tagInternal[0].trim());
        modContent.append(buffer);
      }
      sentence.links.add(tagURL);
      start = m.end();
    }

    if (start > 0) {
      modContent.append(sentence.content.substring(
          start, sentence.content.length()));
      sentence.content = modContent.toString();
    }
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  private boolean addChild(Section candidate, Section child) {
    if (candidate.getLevel() < child.getLevel()) {
      candidate.appendSubSection(child);
      child.setParentSection(candidate);
    } else { // search parent
      Section parent = candidate.getParentSection();
      while (parent != null) {
        if (parent.getLevel() < child.getLevel()) {
          parent.appendSubSection(child);
          child.setParentSection(parent);
          break;
        }
        parent = parent.getParentSection();
      }
      if (parent == null) {
        return false;
      }
    }
    return true;
  }

  private String obtainSentences(int lineNum, String line,
      List<Sentence> outputSentences) {
    String remain = getSentenceExtractor().extract(line, outputSentences);
    for (Sentence sentence : outputSentences) {
      sentence.position = lineNum;
      parseSentence(sentence); // extract inline elements
    }
    return remain;
  }

  private String appendSentencesIntoSection(int lineNum, String line,
      Section currentSection) {
  List<Sentence> outputSentences = new ArrayList<Sentence>();
  String remain = obtainSentences(lineNum, line, outputSentences);

  for (Sentence sentence : outputSentences) {
    currentSection.appendSentence(sentence);
  }
  return remain;
}

  private static boolean check(Pattern p, String target, List<String> head) {
    Matcher m = p.matcher(target);
    if (m.matches()) {
      for (int i = 1; i <= m.groupCount(); i++) {
        head.add(m.group(i));
      }
      return true;
    } else {
      return false;
    }
  }

  private int extractListLevel(String listPrefix) {
    return listPrefix.length();
  }

  private static final Logger LOG = LoggerFactory.getLogger(WikiParser.class);

  /**
   * List of elements used in wiki format.
   */
  private enum LinePattern {
    SENTENCE, LIST, NUM_LIST, VOID, HEADER, COMMENT
  }

  /****************************************************************************
   * patterns to handle wiki syntax.
   ***************************************************************************/

  private static final Pattern HEADER_PATTERN
  = Pattern.compile("^h([1-6])\\. (.*)$");

  private static final Pattern LIST_PATTERN = Pattern.compile("^(-+) (.*)$");

  private static final Pattern NUMBERED_LIST_PATTERN =
      Pattern.compile("^(#+) (.*)$");

  private static final Pattern LINK_PATTERN =
      Pattern.compile("\\[\\[(.*?)\\]\\]");

  private static final Pattern BEGIN_COMMENT_PATTERN =
      Pattern.compile("\\s*^\\[!--");

  private static final Pattern END_COMMENT_PATTERN =
      Pattern.compile("--\\]$\\s*");

  private static final Pattern ITALIC_PATTERN =
      Pattern.compile("//(.+?)//");

  private static final Pattern UNDERLINE_PATTERN =
      Pattern.compile("__(.+?)__");

  private static final Pattern BOLD_PATTERN =
      Pattern.compile("\\*\\*(.+?)\\*\\*");

  private static final Pattern STRIKETHROUGH_PATTERN =
      Pattern.compile("--(.+?)--");

  private static final Pattern [] INLINE_PATTERNS = {
    ITALIC_PATTERN,
    BOLD_PATTERN,
    UNDERLINE_PATTERN,
    STRIKETHROUGH_PATTERN
  };
}
