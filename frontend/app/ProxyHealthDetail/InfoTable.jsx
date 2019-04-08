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

    // static renderResult(props){
    //     return <span>{props.value.known ? "known" : "missing"}
    //     {props.value.haveProxy ? "have": "missing"}
    //     {props.value.wantProxy ? "want" : "dontcare"}
    //     </span>
    // }

    static renderResult(props){
        return <span>
            <ThreeWayIcon iconName="check" state={props.value.haveProxy} onColour="green" hide={!props.value.wantProxy}/>
            <ThreeWayIcon iconName="exclamation" state={props.value.wanted} onColour="orange" hide={false}/>
            <ThreeWayIcon iconName="unlink" state={props.value.known} onColour="black" hide={props.value.known}/>
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
                accessor: "audioResult",
                Cell: InfoTable.renderResult
            },
            {
                Header: "Audio",
                accessor: "audioResult",
                Cell: InfoTable.renderResult
            },
            {
                Header: "Jobs",
                accessor: "fileId",
                Cell: (props)=><Link to={"/admin/jobs?jobid=" + props.value}>View jobs...</Link>
            },
            {
                Header: "Retry",
                accessor: "fileId",
                Cell: (props)=><AttemptRetry itemId={props.value} haveVideo={props.row.videoResult.known}/>
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