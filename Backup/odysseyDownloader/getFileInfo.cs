using System;


namespace Odyssey_Downloader
{
    class getFileInfo
    {
        private string pageSource;
        private string fileUrl;
        private string title;
        private string episodeNumber;
        private string fullTitle;
        private string newFileName;
		CGWebClient webClient = new CGWebClient();

        public string getPageSource()
        {
            return pageSource;
        }
        public string getFileUrl()
        {
            return fileUrl;
        }
        public string getTitle()
        {
            return title;
        }
        public string getEpisodeNumber()
        {
            return episodeNumber;
        }
        public string getFullTitle()
        {
            return fullTitle;
        }
        public string getNewFileName()
        {
            return newFileName;
        }

        private string getPageSource(string URL)
        {
            try
			{
				//System.Net.WebClient webClient = new System.Net.WebClient();
				//webClient.Headers.Add ("user-agent", "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.2; .NET CLR 1.0.3705;)");
	            string strSource = webClient.DownloadString(URL);
	            webClient.Dispose();
				
				return strSource;
			}
			catch (Exception e)
			{
				string except =  e.Message;
				return getPageSource(URL);
			}
        }

        public getFileInfo(config settings, int dayOffset)
        {
            string currentURL = (settings.getUrl()).Replace("$DATE", returnDate(dayOffset, settings.getDateFormat()));
            pageSource = getPageSource(currentURL);
            fileUrl = "http://" + findElement(pageSource, settings.getFileExtension(), "http://") + settings.getFileExtension();
            title = (findElement(pageSource, settings.getTitleEnd(), settings.getTitleStart())).Trim();
            episodeNumber = findElement(fileUrl, settings.getFileExtension(), "_");
            fullTitle = "Episode " + episodeNumber + ": " + title;
            newFileName = episodeNumber + "#-" + title.Replace(" ", "_")+settings.getFileExtension();
        }

        private string returnDate(int dayOffset, string dateFormat)
        {
            DateTime dt = DateTime.Today.AddDays(-dayOffset);
            string date = String.Format("{0:" + dateFormat+"}", dt);
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
