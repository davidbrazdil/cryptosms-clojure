(ns uk.ac.cam.db538.cryptosms.serializables.common)

(defrecord Serializable [ export import length ])

; TYPICAL USAGE
; -------------
; 
; Every serializable is a map containing three fields: export, import (two 
; functions) and length (constant). 
; 
; Export has to be called with one argument, a map with key-value pairs that
; serializable (or sub-serializables) can use to find values it needs to create
; the resulting byte-vector.
; Example: 
;   (def serializable (uint8 :id1))
;   ((:export serializable) {:id1 32})
;   => [ 32 ]
; 
; Import is called with two arguments, a byte-vector (presumably previously
; generated by export of the same serializable) and a map of arguments. This
; is again a key-value pair map. Most of the serializables don't use this, 
; because they can find all the information they need inside the byte-vectors
; or these are given by the serializartion structure. However, some 
; serializables need additional arguments (e.g. decryption needs a key), which 
; can't be saved anywhere else and have to be passed in.
; Example:
;   ; uses serializable from previous example
;   ((:import serializable) [ 32 ] {}) ; no arguments needed
;   => {:id1 32}
; 
;   ; decryption
;   (def crypto-serializable :key serializable)
;   (def crypto-key  ( ... some key ...))
;   (def crypto-result 
;     ((:export crypto-serializable) {:id1 32, :key crypto-key}))
;   ((:import crypto-serializable) crypto-result {:key crypto-key})
;   => {:id1 32}
;
; Length is just a constant generated during construction of the record and 
; can be directly queried.
; Example:
;   (:length (uint8 :id1))
;   => 1
;
