package com.jaspersoft.jrshotfix.pkg;

/**
 * What a package does to one file. Invariants: {@link #ADD} and {@link #REPLACE} are decided
 * against the disk when the package is read (the file is absent or present), and {@link #DELETE}
 * comes only from the package readme.
 */
public enum Action {
  ADD,
  REPLACE,
  DELETE
}
