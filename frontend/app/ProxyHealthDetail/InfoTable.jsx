import React from 'react';
import PropTypes from "prop-types";
import {Link} from "react-router-dom";
import ReactTable, {ReactTableDefaults} from 'react-table';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import ThreeWayIcon from './ThreeWayIcon.jsx';
import AttemptRetry from './AttemptRetry.jsx';

class InfoTable extends React.Component {
    static propTypes = {
        tableData: PropTypes.array.isRequired
    };

    static renderResult(props){
        return <span>
            <ThreeWayIcon iconName="check" title={props.value.haveProxy ? "Proxy exists" : "Proxy absent"} state={props.value.haveProxy} onColour="green" hide={!props.value.wantProxy}/>
            <ThreeWayIcon iconName="exclamation" title={props.value.wantProxy ? "Need proxy" : "Don't need this proxy"} state={props.value.wantProxy} onColour="orange" hide={props.value.haveProxy}/>
            <ThreeWayIcon iconName="unlink" title="Can't find existing proxy" state={props.value.known} onColour="black" hide={props.value.known}/>
        </span>
    }



    constructor(props){
        super(props);

        this.columns = [
            {
                Header: "Collection",
                accessor: "collection",
                Cell: props=><span>{props.value}</span>
            },
            {
                Header: "File Name",
                accessor: "fileName",
                Cell: props=><span>{props.value}</span>
            },
            {
                Header: "Path",
                accessor: "filePath",
                Cell: props=><div><span title={props.value}>{props.value}</span></div>
            },
            {
                Header: "Thumbnail",
                accessor: "thumbnailResult",
                Cell: InfoTable.renderResult
            },
            {
                Header: "Video",
                accessor: "videoResult",
                Cell: InfoTable.renderResult
            },
            {
                Header: "Audio",
                accessor: "audioResult",
                Cell: InfoTable.renderResult
            },
            {
                Header: "Index status",
                accessor: "esRecordSays",
                /* it's an error if this is TRUE, because that would mean ES thinks that there IS a proxy but actually there isn't one. */
                Cell: props=><span>
                        <ThreeWayIcon iconName="check" state={!props.value} onColour="red" hide={!props.value}/>
                        <ThreeWayIcon iconName="times" state={props.value} onColour="green" hide={props.value}/>
                    </span>
            },
            {
                Header: "Jobs",
                accessor: "fileId",
                Cell: (props)=><Link to={"/admin/jobs?sourceId=" + encodeURIComponent(props.value)}>View jobs...</Link>
            },
            {
                Header: "Retry",
                accessor: "fileId",
                Cell: (props)=><AttemptRetry itemId={props.value} haveVideo={props.row.videoResult.haveProxy || !props.row.videoResult.wantProxy}
                                             haveAudio={props.row.audioResult.haveProxy || !props.row.audioResult.wantProxy}
                                             haveThumb={props.row.thumbnailResult.haveProxy || !props.row.thumbnailResult.wantProxy}/>
            }
        ];

        this.style = {
            backgroundColor: '#eee',
            border: '1px solid black',
            borderCollapse: 'collapse'
        };

        this.iconStyle = {
            color: '#aaa',
            paddingLeft: '5px',
            paddingRight: '5px'
        };
    }

    render(){
        return <ReactTable
            data={this.props.tableData}
            columns={this.columns}
            column={Object.assign({}, ReactTableDefaults.column, {headerClassName: 'dashboardheader'})}
            />
    }
}

export default InfoTable;