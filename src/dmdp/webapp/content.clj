(ns dmdp.webapp.content
  (:require [dmdp.layout :as layout]
            [dmdp.dbms.queries :as db]
            [clojure.java.io :as io]
            [ring.util.response :refer [redirect response]]
            [dmdp.webapp.validators :as validators]
            [clojure.string :refer [split]]))

(defn home-page [{:keys [session]}]
  (layout/render
    "home.html" {:identity (:identity session)}))

(defn search-page [{:keys [params session]}]
  (if (empty? params)
    (layout/render
     "content/search.html" {:identity (:identity session)})
      (let [limit (Integer/parseInt (:limit params "20"))
            offset (Integer/parseInt (:offset params "0"))
            title (let [query (:q params)] (if (= nil query) nil (str query)))]
      (layout/render
       "content/search.html" {:publications
                              (cond
                               (not= title nil)
                                 (db/get-publications-by-title {:title title
                                                                :limit limit
                                                                :offset offset})
                               :else (do (println "No filter!\n") []))
                             :authors (db/search-author-by-name {:keyname (str (:q params))
                                                                 :forenames (str (:q params))
                                                                 :limit limit
                                                                 :offset offset})
                             :query (:q params)
                             :identity (:identity session)
                             :prev_page_offset (if (< (- offset limit) 0) 0 (- offset limit))
                             :next_page_offset (+ offset limit)}))))


(defn categories-page [{:keys [params session]}]
  (let [id (:id (:identity session nil) nil)]
    (if (not= id nil)
  (layout/render
   "content/categories/categories.html" {:categories (db/get-categories)
                                         :identity (:identity session)})
  (redirect "/auth/login"))))

(defn authors-page [{:keys [params session]}]
  (let [id (:id (:identity session nil) nil)
        limit (Integer/valueOf (:limit params "10"))
        offset (Integer/valueOf (:offset params "0"))]
    (if (not= id nil)
    (layout/render
     "content/authors/authors.html" {:identity (:identity session)
                                     :authors (db/get-authors {:offset offset
                                                               :limit limit})
                                     :prev_page_offset (if (< (- offset limit) 0) 0 (- offset limit))
                                     :next_page_offset (+ offset limit)})
       (redirect "/auth/login"))))


(defn author-page [{:keys [params session]}]
    (let [user-id (:id (:identity session nil) nil)]
  (if (not= user-id nil)
  (layout/render
   "content/authors/author.html"
     {:author (first (db/get-author params))
      :publications (db/get-publications-by-author {:author_id (:id params)})
      :identity (:identity session)})
          (redirect "/auth/login"))))


(defn publications-page [{:keys [params session]}]
  (let [id (:id (:identity session nil) nil)
        limit (Integer/valueOf (:limit params "10"))
        offset (Integer/valueOf (:offset params "0"))]
   (if (not= id nil)
  (layout/render
   "content/publications/publications.html" {:identity (:identity session)
                                             :publications (db/get-publications {:offset offset
                                                                                 :limit limit})
                                             :prev_page_offset (if (< (- offset limit) 0) 0 (- offset limit))
                                             :next_page_offset (+ offset limit)
                                             :sort_by (:sort_by params "title-asc")})
  (redirect "/auth/login"))))


(defn publication-page [{:keys [params session]}]
  (let [pub-id (:id params)
        user-id (:id (:identity session nil) nil)]
  (if (not= user-id nil)
    (layout/render
     "content/publications/publication.html" {:user (first (db/get-user {:id user-id}))
                                              :publication (first (db/get-publication pub-id))
                                              :authors (db/get-authors-of-publication {:pub_id pub-id})
                                              :categories (db/get-publication-categories {:publication_id pub-id})
                                              :identity (:identity session)})
      (redirect "/auth/login"))))

(defn delete-publication!  [{:keys [params session]}]
  (let [id (:id (:identity session nil) nil)]
    (if (and (not= id nil) (db/check-admin-user id))
  (do
    (db/delete-publication params)
    (redirect "/content/publications"))
    (redirect "/auth/not-admin"))))



(defn new-publication-page [{:keys [params session]}]
  (let [id (:id (:identity session nil) nil)]
    (if (and (not= id nil) (db/check-admin-user id))
  (layout/render
   "content/publications/new_publication.html" {:identity (:identity session)})
      (redirect "/auth/not-admin"))))

(defn add-new-publication! [{:keys [params]}]
  (do
    (let [pub-id (db/create-publication params)
          a-id   (db/create-author params)]

    (db/bind-publication-to-author! {:author_id a-id
                                     :publication_id pub-id})

    (redirect (str "/content/publications/" pub-id)))))

(defn edit-publication-page [{:keys [params session]}]
(let [id (:id (:identity session nil) nil)]
    (if (and (not= id nil) (db/check-admin-user id))
      (layout/render
         "content/publications/edit_publication.html" {:user (first (db/get-user {:id (:id (:identity session))}))
                                                       :publication (first (db/get-publication id))
                                                       :identity (:identity session)})
      (redirect (str "/content/publications/" (:id params))))))

(defn edit-publication! [{:keys [params]}]
  (if (validators/validate-publication-edition params)
    (do
      (db/update-publication!
                       {:id (Integer/valueOf (:id params))
                        :title (:title params)
                        :uid (:uid params)
                        :abstract (:abstract params)
                        :doi (:doi params)
                        :journal_ref (:journal_ref params)
                        :comments (:comments params)
                        :date_created (:date_created params)
                        :date_updated (:date_updated params)})
      (redirect (str "/content/publications/" (:id params))))
    (redirect (str "/content/publications/" (:id params) "/edit"))))
