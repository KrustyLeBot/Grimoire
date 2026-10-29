package main

import (
	"encoding/json"
	"strings"
)

// What the app receives: Reddit's data, cleaned up and flattened. Markdown and HTML are both kept
// (the Android app renders the HTML, another client may prefer the Markdown).

type Image struct {
	URL     string `json:"url"`
	Width   int    `json:"w,omitempty"`
	Height  int    `json:"h,omitempty"`
	Caption string `json:"caption,omitempty"`
	// Animated images (GIFs) also come as an MP4, much lighter.
	MP4 string `json:"mp4,omitempty"`
}

type Video struct {
	HLS      string `json:"hls,omitempty"`
	MP4      string `json:"mp4,omitempty"` // Reddit's fallback MP4 has no sound
	Width    int    `json:"w,omitempty"`
	Height   int    `json:"h,omitempty"`
	Duration int    `json:"duration,omitempty"`
}

type Media struct {
	Kind   string  `json:"kind"` // text, image, gallery, video, link
	Images []Image `json:"images,omitempty"`
	Video  *Video  `json:"video,omitempty"`
}

type Post struct {
	ID         string  `json:"id"`
	Name       string  `json:"name"` // fullname, t3_<id>: the "after" of the next page
	Sub        string  `json:"sub"`
	Title      string  `json:"title"`
	Author     string  `json:"author"`
	Flair      string  `json:"flair,omitempty"`
	FlairColor string  `json:"flairColor,omitempty"`
	Score      int     `json:"score"`
	Ratio      float64 `json:"ratio"`
	Comments   int     `json:"comments"`
	Created    int64   `json:"created"`
	Edited     bool    `json:"edited,omitempty"`
	Stickied   bool    `json:"stickied,omitempty"`
	NSFW       bool    `json:"nsfw,omitempty"`
	Spoiler    bool    `json:"spoiler,omitempty"`
	Locked     bool    `json:"locked,omitempty"`
	Text       string  `json:"text,omitempty"`
	HTML       string  `json:"html,omitempty"`
	URL        string  `json:"url,omitempty"`
	Domain     string  `json:"domain,omitempty"`
	Permalink  string  `json:"permalink"`
	Thumb      string  `json:"thumb,omitempty"`
	Media      Media   `json:"media"`
}

// Comment is one line of a thread, in display order; Depth gives the indentation. Kind "more" stands
// for replies Reddit did not send (Count of them): Children are their ids, for /more.
type Comment struct {
	Kind       string   `json:"kind"` // comment, more
	ID         string   `json:"id"`
	Parent     string   `json:"parent"` // fullname: t3_ (the post) or t1_ (a comment)
	Depth      int      `json:"depth"`
	Author     string   `json:"author,omitempty"`
	Flair      string   `json:"flair,omitempty"`
	Text       string   `json:"text,omitempty"`
	HTML       string   `json:"html,omitempty"`
	Score      int      `json:"score,omitempty"`
	ScoreHide  bool     `json:"scoreHidden,omitempty"`
	Created    int64    `json:"created,omitempty"`
	Edited     bool     `json:"edited,omitempty"`
	OP         bool     `json:"op,omitempty"`
	Mod        bool     `json:"mod,omitempty"`
	Stickied   bool     `json:"stickied,omitempty"`
	Collapsed  bool     `json:"collapsed,omitempty"`
	Deleted    bool     `json:"deleted,omitempty"`
	Count      int      `json:"count,omitempty"`
	Children   []string `json:"children,omitempty"`
	ContinueAt string   `json:"continueAt,omitempty"` // "continue this thread": open /thread/<post>?comment=<this>
}

type Feed struct {
	Sub     string `json:"sub"`
	Sort    string `json:"sort"`
	Posts   []Post `json:"posts"`
	After   string `json:"after,omitempty"`
	Fetched int64  `json:"fetched"`
	Stale   bool   `json:"stale,omitempty"`
}

type Thread struct {
	Post     Post      `json:"post"`
	Comments []Comment `json:"comments"`
	Fetched  int64     `json:"fetched"`
	Stale    bool      `json:"stale,omitempty"`
}

// ---- Reddit's own shapes (only what is read) ----

type thing struct {
	Kind string          `json:"kind"`
	Data json.RawMessage `json:"data"`
}

type listing struct {
	After    string  `json:"after"`
	Children []thing `json:"children"`
}

type mediaSource struct {
	URL    string `json:"url"`
	U      string `json:"u"`
	GIF    string `json:"gif"`
	MP4    string `json:"mp4"`
	Width  int    `json:"width"`
	Height int    `json:"height"`
	X      int    `json:"x"`
	Y      int    `json:"y"`
}

type redditVideo struct {
	HLS      string `json:"hls_url"`
	Fallback string `json:"fallback_url"`
	Width    int    `json:"width"`
	Height   int    `json:"height"`
	Duration int    `json:"duration"`
}

type rawPost struct {
	ID           string          `json:"id"`
	Name         string          `json:"name"`
	Subreddit    string          `json:"subreddit"`
	Title        string          `json:"title"`
	Author       string          `json:"author"`
	Flair        string          `json:"link_flair_text"`
	FlairBG      string          `json:"link_flair_background_color"`
	Score        int             `json:"score"`
	Ratio        float64         `json:"upvote_ratio"`
	NumComments  int             `json:"num_comments"`
	Created      float64         `json:"created_utc"`
	Edited       json.RawMessage `json:"edited"`
	Stickied     bool            `json:"stickied"`
	Over18       bool            `json:"over_18"`
	Spoiler      bool            `json:"spoiler"`
	Locked       bool            `json:"locked"`
	IsSelf       bool            `json:"is_self"`
	Selftext     string          `json:"selftext"`
	SelftextHTML string          `json:"selftext_html"`
	URL          string          `json:"url"`
	Domain       string          `json:"domain"`
	Permalink    string          `json:"permalink"`
	Thumbnail    string          `json:"thumbnail"`
	PostHint     string          `json:"post_hint"`
	IsGallery    bool            `json:"is_gallery"`
	GalleryData  *struct {
		Items []struct {
			MediaID string `json:"media_id"`
			Caption string `json:"caption"`
		} `json:"items"`
	} `json:"gallery_data"`
	MediaMetadata map[string]struct {
		Status string      `json:"status"`
		S      mediaSource `json:"s"`
	} `json:"media_metadata"`
	Preview *struct {
		Images []struct {
			Source      mediaSource   `json:"source"`
			Resolutions []mediaSource `json:"resolutions"`
			Variants    struct {
				MP4 *struct {
					Source mediaSource `json:"source"`
				} `json:"mp4"`
			} `json:"variants"`
		} `json:"images"`
	} `json:"preview"`
	Media *struct {
		RedditVideo *redditVideo `json:"reddit_video"`
	} `json:"media"`
	CrosspostParents []json.RawMessage `json:"crosspost_parent_list"`
}

type rawComment struct {
	ID          string          `json:"id"`
	Name        string          `json:"name"`
	ParentID    string          `json:"parent_id"`
	Depth       int             `json:"depth"`
	Author      string          `json:"author"`
	Flair       string          `json:"author_flair_text"`
	Body        string          `json:"body"`
	BodyHTML    string          `json:"body_html"`
	Score       int             `json:"score"`
	ScoreHidden bool            `json:"score_hidden"`
	Created     float64         `json:"created_utc"`
	Edited      json.RawMessage `json:"edited"`
	IsSubmitter bool            `json:"is_submitter"`
	Distinguish string          `json:"distinguished"`
	Stickied    bool            `json:"stickied"`
	Collapsed   bool            `json:"collapsed"`
	Replies     json.RawMessage `json:"replies"`
	// "more" things
	Count    int      `json:"count"`
	Children []string `json:"children"`
}

// edited is false, or the time of the edit.
func edited(raw json.RawMessage) bool {
	s := strings.TrimSpace(string(raw))
	return s != "" && s != "false" && s != "null"
}

func httpURL(s string) string {
	if strings.HasPrefix(s, "https://") || strings.HasPrefix(s, "http://") {
		return s
	}
	return ""
}

func cleanPost(r rawPost) Post {
	p := Post{
		ID: r.ID, Name: r.Name, Sub: r.Subreddit, Title: r.Title, Author: r.Author,
		Flair: r.Flair, FlairColor: r.FlairBG, Score: r.Score, Ratio: r.Ratio, Comments: r.NumComments,
		Created: int64(r.Created), Edited: edited(r.Edited), Stickied: r.Stickied, NSFW: r.Over18,
		Spoiler: r.Spoiler, Locked: r.Locked, Permalink: r.Permalink, Domain: r.Domain,
	}
	if r.IsSelf {
		p.Text, p.HTML = r.Selftext, r.SelftextHTML
	} else {
		p.URL = r.URL
	}
	p.Media = mediaOf(r)
	if p.Media.Kind == "text" || p.Media.Kind == "link" {
		// A crosspost carries its media in the original post.
		for _, raw := range r.CrosspostParents {
			var parent rawPost
			if json.Unmarshal(raw, &parent) == nil {
				if m := mediaOf(parent); m.Kind != "text" && m.Kind != "link" {
					p.Media = m
					break
				}
			}
		}
	}
	p.Thumb = thumbOf(r)
	return p
}

func mediaOf(r rawPost) Media {
	if r.Media != nil && r.Media.RedditVideo != nil {
		v := r.Media.RedditVideo
		return Media{Kind: "video", Video: &Video{HLS: v.HLS, MP4: v.Fallback, Width: v.Width, Height: v.Height, Duration: v.Duration}}
	}
	if r.IsGallery && r.GalleryData != nil {
		var imgs []Image
		for _, it := range r.GalleryData.Items {
			m, ok := r.MediaMetadata[it.MediaID]
			if !ok || (m.Status != "" && m.Status != "valid") {
				continue
			}
			u := m.S.U
			if u == "" {
				u = m.S.GIF
			}
			if u = httpURL(u); u != "" {
				imgs = append(imgs, Image{URL: u, Width: m.S.X, Height: m.S.Y, Caption: it.Caption, MP4: httpURL(m.S.MP4)})
			}
		}
		if len(imgs) > 0 {
			return Media{Kind: "gallery", Images: imgs}
		}
	}
	if r.PostHint == "image" && r.Preview != nil && len(r.Preview.Images) > 0 {
		pi := r.Preview.Images[0]
		img := Image{URL: httpURL(pi.Source.URL), Width: pi.Source.Width, Height: pi.Source.Height}
		if pi.Variants.MP4 != nil {
			img.MP4 = httpURL(pi.Variants.MP4.Source.URL)
		}
		if strings.HasSuffix(strings.ToLower(r.URL), ".gif") && img.MP4 == "" {
			img.URL = r.URL
		}
		if img.URL != "" {
			return Media{Kind: "image", Images: []Image{img}}
		}
	}
	if r.IsSelf {
		return Media{Kind: "text"}
	}
	return Media{Kind: "link"}
}

// thumbOf picks a preview of about 320 px wide; Reddit's "thumbnail" can be "self", "default", "nsfw"…
func thumbOf(r rawPost) string {
	if r.Preview != nil && len(r.Preview.Images) > 0 {
		res := r.Preview.Images[0].Resolutions
		for _, s := range res {
			if s.Width >= 320 {
				return httpURL(s.URL)
			}
		}
		if len(res) > 0 {
			return httpURL(res[len(res)-1].URL)
		}
	}
	return httpURL(r.Thumbnail)
}

func deletedText(s string) bool { return s == "[deleted]" || s == "[removed]" }

func cleanComment(c rawComment, postID string) Comment {
	out := Comment{
		Kind: "comment", ID: c.ID, Parent: c.ParentID, Depth: c.Depth, Author: c.Author, Flair: c.Flair,
		Text: c.Body, HTML: c.BodyHTML, Score: c.Score, ScoreHide: c.ScoreHidden, Created: int64(c.Created),
		Edited: edited(c.Edited), OP: c.IsSubmitter, Mod: c.Distinguish == "moderator" || c.Distinguish == "admin",
		Stickied: c.Stickied, Collapsed: c.Collapsed,
	}
	out.Deleted = deletedText(c.Body) || deletedText(c.Author)
	return out
}

// flatten walks a comment listing depth first, in Reddit's order.
func flatten(children []thing, postID string, out []Comment) []Comment {
	for _, t := range children {
		var c rawComment
		if json.Unmarshal(t.Data, &c) != nil {
			continue
		}
		switch t.Kind {
		case "t1":
			out = append(out, cleanComment(c, postID))
			if len(c.Replies) > 0 && c.Replies[0] == '{' {
				var replies thing
				var l listing
				if json.Unmarshal(c.Replies, &replies) == nil && json.Unmarshal(replies.Data, &l) == nil {
					out = flatten(l.Children, postID, out)
				}
			}
		case "more":
			m := Comment{Kind: "more", ID: c.ID, Parent: c.ParentID, Depth: c.Depth, Count: c.Count, Children: c.Children}
			// A "more" without children is Reddit's "continue this thread": the replies are only
			// reachable by opening the parent comment on its own.
			if len(c.Children) == 0 || c.ID == "_" {
				m.ContinueAt = strings.TrimPrefix(c.ParentID, "t1_")
				m.Children = nil
			}
			if m.Count == 0 && m.ContinueAt == "" && len(m.Children) == 0 {
				continue
			}
			out = append(out, m)
		}
	}
	return out
}

func parseFeed(body []byte, sub, sort string) (*Feed, error) {
	var t thing
	var l listing
	if err := json.Unmarshal(body, &t); err != nil {
		return nil, err
	}
	if err := json.Unmarshal(t.Data, &l); err != nil {
		return nil, err
	}
	f := &Feed{Sub: sub, Sort: sort, After: l.After, Posts: []Post{}}
	for _, c := range l.Children {
		if c.Kind != "t3" {
			continue
		}
		var r rawPost
		if json.Unmarshal(c.Data, &r) == nil {
			f.Posts = append(f.Posts, cleanPost(r))
		}
	}
	return f, nil
}

// parseThread reads /comments/<id>.json: [post listing, comments listing].
func parseThread(body []byte) (*Thread, error) {
	var parts []thing
	if err := json.Unmarshal(body, &parts); err != nil {
		return nil, err
	}
	if len(parts) < 2 {
		return nil, errBadShape
	}
	var pl, cl listing
	if err := json.Unmarshal(parts[0].Data, &pl); err != nil || len(pl.Children) == 0 {
		return nil, errBadShape
	}
	var r rawPost
	if err := json.Unmarshal(pl.Children[0].Data, &r); err != nil {
		return nil, err
	}
	if err := json.Unmarshal(parts[1].Data, &cl); err != nil {
		return nil, err
	}
	post := cleanPost(r)
	return &Thread{Post: post, Comments: flatten(cl.Children, post.ID, []Comment{})}, nil
}

// parseMore reads /api/morechildren: a flat list of things (in display order) whose parents are
// either the stub's parent or one of the things before them.
func parseMore(body []byte, postID string) ([]Comment, error) {
	var resp struct {
		JSON struct {
			Errors [][]any `json:"errors"`
			Data   struct {
				Things []thing `json:"things"`
			} `json:"data"`
		} `json:"json"`
	}
	if err := json.Unmarshal(body, &resp); err != nil {
		return nil, err
	}
	if len(resp.JSON.Errors) > 0 {
		return nil, errBadShape
	}
	return flatten(resp.JSON.Data.Things, postID, []Comment{}), nil
}
