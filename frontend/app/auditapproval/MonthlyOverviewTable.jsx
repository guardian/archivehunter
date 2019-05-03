import React from 'react';
import axios from 'axios';
import PropTypes from 'prop-types';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import BytesFormatter from '../common/BytesFormatter.jsx';
import moment from 'moment';
import LoadingThrobber from "../common/LoadingThrobber.jsx";
import ReactTable from 'react-table';
import { ReactTableDefaults } from 'react-table';
import 'react-table/react-table.css'

class MonthlyOverviewTable extends React.Component {
    constructor(props){
        super(props);
        this.state = {
            loading: false,
            lastError: null,
            tableData:[]
        };

        this.columns = [
            {
                Header: "Month",
                accessor: "dateValue",
                Cell: props=>moment(props.value).format("MMM YYYY")
            },
            {
                Header: "Data restored from Glacier tier",
                accessor: "restoredData",
                Cell: props=><BytesFormatter value={props.value}/>
            },
            {
                Header: "Data downloaded from the system",
                accessor: "downloadedData",
                Cell: props=><BytesFormatter value={props.value}/>
            }
        ];
    }

    entryForClass(className, entryList){
        const validEntries = entryList.filter(entry=>entry.auditClass===className)
        if(validEntries.length>0){
            return validEntries[0].value;
        } else {
            return null;
        }
    }

    componentWillMount() {
        this.setState({loading:true},()=>axios.get("/api/audit/monthlyOverview").then(response=>{
            this.setState({
                loading: false,
                lastError: null,
                tableData: response.data.map(entry=>{ return {
                    dateValue:entry.dateValue,
                    restoredData: this.entryForClass("Restore", entry.entries),
                    downloadedData: this.entryForClass("Download", entry.entries)
                }})
            })
        }).catch(err =>{
            console.error(err);
            this.setState({loading: false, lastError: err});
        }));
    }

    render(){
        if(this.state.lastError) return <ErrorViewComponent error={this.state.lastError}/>;
        if(this.state.loading) return <LoadingThrobber show={true} caption="Loading..."/>;

        return <div className="chart-container">
            <p className="information">Note: due to Amazon's pricing model the costs here represent the worst-case scenario,
                i.e. cost at the highest applicable rate without any external factors taken into account. The actual amount
                spent is going to be less than that shown here.</p>
            <ReactTable
                data={this.state.tableData}
                columns={this.columns}
                column={Object.assign({}, ReactTableDefaults.column, {headerClassName: 'dashboardheader'})}
                defaultSorted={[{
                    id: 'dateValue',
                    desc: true
                }]}
            />
        </div>
    }
}

export default MonthlyOverviewTable;
