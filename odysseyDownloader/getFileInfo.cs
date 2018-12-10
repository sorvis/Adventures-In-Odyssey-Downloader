using System;


namespace Odyssey_Downloader
{
    internal class GetFileInfo
    {
        private string pageSource;
        private string fileUrl;
        private string title;
        private string episodeNumber;
        private string fullTitle;
        private string newFileName;
		CGWebClient webClient = new CGWebClient();

        public string GetPageSource()
        {
            return pageSource;
        }

        public string GetFileUrl()
        {
            return fileUrl;
        }

        public string GetTitle()
        {
            return title;
        }

        public string GetEpisodeNumber()
        {
            return episodeNumber;
        }

        public string GetFullTitle()
        {
            return fullTitle;
        }

        public string FileName
        {
            get
            {
                return filterOutInvalidFileNameChars(newFileName);
            }
        }

        private static string filterOutInvalidFileNameChars(string fileName)
        {
            string invalidChars = System.Text.RegularExpressions.Regex.Escape(new string(System.IO.Path.GetInvalidFileNameChars()));
            string invalidReStr = string.Format(@"([{0}]*\.+$)|([{0}]+)", invalidChars);
            return System.Text.RegularExpressions.Regex.Replace(fileName, invalidReStr, "_");
        }

        private string getPageSource(string url)
        {
            try
			{
				//System.Net.WebClient webClient = new System.Net.WebClient();
				//webClient.Headers.Add ("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.2; .NET CLR 1.0.3705;)");
	            string strSource = webClient.DownloadString(url);
	            webClient.Dispose();

				return strSource;
			}
			catch (Exception e)
			{
				string except =  e.Message;
				return getPageSource(url);
			}
        }

        public GetFileInfo(Config settings, int dayOffset)
        {
            string currentURL = (settings.Url).Replace("$DATE", returnDate(dayOffset, settings.DateFormat));
            pageSource = getPageSource(currentURL);
            fileUrl = "http://" + findElement(pageSource, settings.FileExtension, "http://") + settings.FileExtension;
            title = (findElement(pageSource, settings.TitleEnd, settings.TitleStart)).Trim();
            episodeNumber = findElement(fileUrl, settings.FileExtension, "_");
            fullTitle = "Episode " + episodeNumber + ": " + title;
            newFileName = episodeNumber + "#-" + title.Replace(" ", "_")+settings.FileExtension;
        }

        private string returnDate(int dayOffset, string dateFormat)
        {
            DateTime dt = DateTime.Today.AddDays(-dayOffset);
            string date = string.Format("{0:" + dateFormat+"}", dt);
            return date;
        }

        private string findElement(string Source, string elementEnd, string elementStart)
        {
            int extensionIndex = Source.IndexOf(elementEnd);
            Source = Source.Substring(0, extensionIndex); //cut off source after file
            Source = ReverseString(Source); // reverse the source so that the file url is first
            int httpIndex = Source.IndexOf(ReverseString(elementStart));
            Source = Source.Substring(0, httpIndex); //cut off source before file
            Source = ReverseString(Source); // reverse the URL back to normal

            return Source;
        }

        private static string ReverseString(string s)
        {
            char[] arr = s.ToCharArray();
            Array.Reverse(arr);
            return new string(arr);
        }
    }
}
