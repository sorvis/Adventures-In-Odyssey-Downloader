using Odyssey_Downloader;
using Odyssey_Downloader.Model;
using System.Collections.Generic;

namespace Adventures_In_Odyssey_Downloader.FileReaderV2
{
    public class FileIndexV2 : IIndexReader
    {
        public bool IndexDetected()
        {
            throw new System.NotImplementedException();
        }

        public IEnumerable<AudioFile> ReadIndex()
        {
            throw new System.NotImplementedException();
        }

        public IEnumerable<AudioFile> RebuildIndex()
        {
            return null;
        }

        public void WriteToIndex(AudioFile fileInfo)
        {
            throw new System.NotImplementedException();
        }
    }
}