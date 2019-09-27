using System;

namespace Odyssey_Downloader.Model
{
    public class AudioFile
    {
        public AudioFile()
        {

        }

        public AudioFile(IFileInfo file)
        {
            Title = file.Title;
            FileName = file.FileName;
        }

        public string Title { get; set; }

        public string FileName { get; set; }

        public string Number { get; set; }

        public string Date { get; set; }

        public void SetDate(DateTime date)
        {

        }
    }
}