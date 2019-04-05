import React from 'react';
import PropTypes from "prop-types";
import {Link} from "react-router-dom";
import ReactTable, {ReactTableDefaults} from 'react-table';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'

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
            {InfoTable.threeWayIcon("check", props.value.haveProxy, "green", !props.value.wantProxy)}
            {InfoTable.threeWayIcon("exclamation", props.value.wantProxy, "orange", false)}
            {InfoTable.threeWayIcon("unlink", props.value.known, "black", props.value.known)}
        </span>
    }

    static threeWayIcon(iconName, state, truecolour, hide){
        const colour = state ? truecolour : "grey";
        const display = hide ? "none" : "inline";

        return <FontAwesomeIcon icon={iconName}  size="1.5x" style={{display: display, color: colour, marginLeft: "1em", marginRight: "1em"}}/>
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
                Cell: (props)=><Link to={"/admin/jobs?jobId=" + props.value.value}>View jobs...</Link>
            },
            {
                Header: "Retry",
                accessor: "fileId",
                Cell: (props)=><a href="#" onClick={()=>this.attemptRetry(props.value)}>Attempt retry...</a>
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