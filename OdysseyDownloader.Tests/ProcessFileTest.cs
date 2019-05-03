using Moq;
using Odyssey_Downloader;
using System;
using System.Collections.Generic;
using System.Text;
using Xunit;

namespace OdysseyDownloader.Tests
{
    public class ProcessFileTest
    {
        Mock<IGetFileInfo> _fileInfoMock;
        private ProcessFileTest it;

        public ProcessFileTest()
        {
            _fileInfoMock = new Mock<IGetFileInfo>();
            it = new ProcessFile();
        }

        [Fact]
        public void test()
        {

        }
    }
}
